package com.joopapa.familyalbum.media;

import com.joopapa.familyalbum.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final Duration UNSETTLED_INLINE_URL_TTL = Duration.ofSeconds(60);

    private final MediaAssetRepository mediaAssetRepository;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties storageProperties;
    private final Executor mediaProcessingExecutor;
    private final Set<UUID> queuedProcessingAssetIds = ConcurrentHashMap.newKeySet();

    public MediaService(
            MediaAssetRepository mediaAssetRepository,
            S3Client s3Client,
            S3Presigner s3Presigner,
            StorageProperties storageProperties,
            @Qualifier("mediaProcessingExecutor") Executor mediaProcessingExecutor
    ) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.storageProperties = storageProperties;
        this.mediaProcessingExecutor = mediaProcessingExecutor;
    }

    @Transactional(readOnly = true)
    public List<MediaDtos.MediaAssetResponse> listAssets() {
        return mediaAssetRepository.findTimeline().stream()
                .map(MediaDtos.MediaAssetResponse::from)
                .toList();
    }
    @Transactional(readOnly = true)
    public MediaDtos.MediaPageResponse listAssetsPage(String cursor, int limit) {
        int pageSize = Math.clamp(limit, 1, 80);
        TimelineCursor timelineCursor = decodeCursor(cursor);
        PageRequest pageRequest = PageRequest.of(0, pageSize + 1);
        List<MediaAsset> page = timelineCursor.date() == null
                ? mediaAssetRepository.findTimelinePage(pageRequest)
                : mediaAssetRepository.findTimelinePageAfter(
                        timelineCursor.date(),
                        timelineCursor.createdAt(),
                        pageRequest
                );
        boolean hasMore = page.size() > pageSize;
        List<MediaAsset> visiblePage = hasMore ? page.subList(0, pageSize) : page;
        String nextCursor = hasMore && !visiblePage.isEmpty()
                ? encodeCursor(visiblePage.getLast())
                : null;
        return new MediaDtos.MediaPageResponse(
                visiblePage.stream().map(MediaDtos.MediaAssetResponse::from).toList(),
                nextCursor,
                hasMore
        );
    }
    @Transactional(readOnly = true)
    public MediaDtos.BackfillMediaResponse enqueueMissingWebAssets(int limit) {
        int batchSize = Math.clamp(limit, 1, 500);
        List<MediaAsset> candidates = mediaAssetRepository.findWebAssetBackfillCandidates(
                UploadStatus.UPLOADED,
                MediaType.VIDEO,
                MediaType.IMAGE,
                PageRequest.of(0, batchSize)
        );
        int queuedCount = 0;
        int alreadyQueuedCount = 0;
        int thumbnailMissingCount = 0;
        int previewMissingCount = 0;
        for (MediaAsset asset : candidates) {
            if (!asset.hasThumbnail()) {
                thumbnailMissingCount++;
            }
            if (needsPreviewGeneration(asset) && !asset.hasPreview()) {
                previewMissingCount++;
            }
            if (needsWebAssetGeneration(asset)) {
                if (enqueueWebAssetGeneration(asset.getId())) {
                    queuedCount++;
                } else {
                    alreadyQueuedCount++;
                }
            }
        }
        return new MediaDtos.BackfillMediaResponse(
                candidates.size(),
                queuedCount,
                alreadyQueuedCount,
                thumbnailMissingCount,
                previewMissingCount
        );
    }
    private record TimelineCursor(Instant date, Instant createdAt) {
    }

    private static TimelineCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new TimelineCursor(null, null);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                return new TimelineCursor(null, null);
            }
            return new TimelineCursor(Instant.parse(parts[0]), Instant.parse(parts[1]));
        } catch (RuntimeException exception) {
            return new TimelineCursor(null, null);
        }
    }

    private static String encodeCursor(MediaAsset asset) {
        String raw = assetDate(asset) + "|" + asset.getCreatedAt();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant assetDate(MediaAsset asset) {
        return asset.getCapturedAt() != null ? asset.getCapturedAt() : asset.getCreatedAt();
    }

    @Transactional
    public MediaDtos.MediaAssetResponse uploadFile(MultipartFile file, Instant capturedAt) {
        String filename = cleanFilename(file.getOriginalFilename());
        String contentType = normalizeContentType(file.getContentType(), filename);
        MediaType mediaType = detectMediaType(contentType);

        Path uploadFile = null;
        MediaAsset asset = null;
        try {
            uploadFile = Files.createTempFile("familyalbum-upload-", extensionOf(filename));
            file.transferTo(uploadFile);
            String contentHash = sha256Hex(uploadFile);
            MediaAsset duplicate = mediaAssetRepository
                    .findFirstByContentHashAndUploadStatus(contentHash, UploadStatus.UPLOADED)
                    .orElse(null);
            if (duplicate != null) {
                return MediaDtos.MediaAssetResponse.from(duplicate, true);
            }

            String objectKey = createOriginalObjectKey(filename);
            asset = mediaAssetRepository.save(new MediaAsset(
                    objectKey,
                    filename,
                    contentType,
                    mediaType,
                    file.getSize(),
                    capturedAt
            ));
            asset.setContentHash(contentHash);

            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(storageProperties.bucket())
                            .key(objectKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromFile(uploadFile)
            );
            verifyUploadedObject(asset);
            asset.markUploaded();
            enqueueWebAssetGeneration(asset.getId());
            return MediaDtos.MediaAssetResponse.from(asset);
        } catch (IOException exception) {
            if (asset != null) {
                asset.markFailed();
            }
            throw new IllegalStateException("Failed to read uploaded file", exception);
        } catch (RuntimeException exception) {
            if (asset != null) {
                asset.markFailed();
            }
            throw exception;
        } finally {
            deleteTempFile(uploadFile);
        }
    }

    @Transactional
    public MediaDtos.CreateUploadUrlResponse createUploadUrl(MediaDtos.CreateUploadUrlRequest request) {
        String contentType = normalizeContentType(request.contentType(), request.filename());
        MediaType mediaType = detectMediaType(contentType);
        String objectKey = createOriginalObjectKey(request.filename());
        MediaAsset asset = mediaAssetRepository.save(new MediaAsset(
                objectKey,
                cleanFilename(request.filename()),
                contentType,
                mediaType,
                request.byteSize(),
                request.capturedAt()
        ));

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(storageProperties.uploadUrlTtl())
                .putObjectRequest(putObjectRequest)
                .build();
        Instant expiresAt = Instant.now().plus(storageProperties.uploadUrlTtl());

        return new MediaDtos.CreateUploadUrlResponse(
                asset.getId(),
                objectKey,
                s3Presigner.presignPutObject(presignRequest).url().toString(),
                expiresAt
        );
    }

    @Transactional
    public MediaDtos.MediaAssetResponse completeUpload(UUID assetId) {
        MediaAsset asset = mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Media asset not found"));
        verifyUploadedObject(asset);
        asset.markUploaded();
        enqueueWebAssetGeneration(asset.getId());
        return MediaDtos.MediaAssetResponse.from(asset);
    }

    @Transactional(readOnly = true)
    public MediaAsset getAsset(UUID assetId) {
        return mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Media asset not found"));
    }

    @Transactional(readOnly = true)
    public MediaDtos.DownloadUrlResponse createDownloadUrl(UUID assetId) {
        MediaAsset asset = getAsset(assetId);
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(asset.getOriginalObjectKey())
                .responseContentDisposition("attachment; filename=\"" + asset.getOriginalFilename() + "\"")
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(storageProperties.downloadUrlTtl())
                .getObjectRequest(getObjectRequest)
                .build();

        return new MediaDtos.DownloadUrlResponse(
                s3Presigner.presignGetObject(presignRequest).url().toString(),
                Instant.now().plus(storageProperties.downloadUrlTtl())
        );
    }

    @Transactional(readOnly = true)
    public String createViewUrl(UUID assetId) {
        MediaAsset asset = getAsset(assetId);
        if (asset.hasPreview()) {
            return createInlineUrl(asset.getPreviewObjectKey(), asset.getPreviewContentType(), previewFilename(asset));
        }
        return createInlineUrl(asset.getOriginalObjectKey(), asset.getContentType(), asset.getOriginalFilename());
    }

    public record CachedMediaObject(
            String objectKey,
            String contentType,
            String filename,
            Duration browserCacheTtl,
            Instant lastModified,
            String eTag,
            byte[] inlineContent
    ) {
        boolean hasInlineContent() {
            return inlineContent != null && inlineContent.length > 0;
        }
    }

    @Transactional
    public CachedMediaObject getThumbnailObject(UUID assetId) {
        MediaAsset asset = getAsset(assetId);
        boolean settled = !needsWebAssetGeneration(asset);
        if (!settled) {
            enqueueWebAssetGeneration(asset.getId());
        }
        Duration ttl = settled ? storageProperties.inlineUrlTtl() : UNSETTLED_INLINE_URL_TTL;

        if (asset.hasThumbnail()) {
            return cachedObject(asset.getThumbnailObjectKey(), "image/jpeg", stemOf(asset.getOriginalFilename()) + ".jpg", ttl, asset);
        }
        if (asset.hasPreview() && asset.getPreviewContentType() != null && asset.getPreviewContentType().startsWith("image/")) {
            return cachedObject(asset.getPreviewObjectKey(), asset.getPreviewContentType(), previewFilename(asset), ttl, asset);
        }
        if (asset.getMediaType() == MediaType.IMAGE && !isHeifImage(asset)) {
            return cachedObject(asset.getOriginalObjectKey(), asset.getContentType(), asset.getOriginalFilename(), UNSETTLED_INLINE_URL_TTL, asset);
        }
        return placeholderObject(asset);
    }

    private CachedMediaObject cachedObject(String objectKey, String contentType, String filename, Duration ttl, MediaAsset asset) {
        return new CachedMediaObject(
                objectKey,
                contentType,
                filename,
                ttl,
                asset.getUpdatedAt(),
                "W/\"" + asset.getId() + ":" + objectKey.hashCode() + ":" + asset.getUpdatedAt().toEpochMilli() + "\"",
                null
        );
    }


    private CachedMediaObject placeholderObject(MediaAsset asset) {
        String label = asset.getMediaType() == MediaType.VIDEO ? "Video" : "Preview";
        String svg = """
                <svg xmlns=\"http://www.w3.org/2000/svg\" width=\"640\" height=\"640\" viewBox=\"0 0 640 640\">
                  <rect width=\"640\" height=\"640\" fill=\"#e7f6ff\"/>
                  <circle cx=\"320\" cy=\"292\" r=\"78\" fill=\"#8fcdf4\"/>
                  <text x=\"320\" y=\"420\" text-anchor=\"middle\" font-family=\"Arial, sans-serif\" font-size=\"34\" font-weight=\"700\" fill=\"#2f78a8\">%s</text>
                </svg>
                """.formatted(label);
        return new CachedMediaObject(
                null,
                "image/svg+xml;charset=UTF-8",
                "thumbnail.svg",
                UNSETTLED_INLINE_URL_TTL,
                asset.getUpdatedAt(),
                "W/\"" + asset.getId() + ":placeholder:" + asset.getUpdatedAt().toEpochMilli() + "\"",
                svg.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Transactional(readOnly = true)
    public void writeOriginalFile(UUID assetId, OutputStream outputStream) throws IOException {
        MediaAsset asset = getAsset(assetId);
        writeObject(asset.getOriginalObjectKey(), outputStream);
    }

    public void writeObject(String objectKey, OutputStream outputStream) throws IOException {
        try (ResponseInputStream<GetObjectResponse> objectStream = s3Client.getObject(GetObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(objectKey)
                .build())) {
            objectStream.transferTo(outputStream);
        }
    }

    @Transactional
    public MediaDtos.DeleteMediaResponse deleteAsset(UUID assetId) {
        MediaAsset asset = getAsset(assetId);
        deleteObjects(asset);
        mediaAssetRepository.delete(asset);
        return new MediaDtos.DeleteMediaResponse(1);
    }

    @Transactional
    public MediaDtos.DeleteMediaResponse deleteAssets(List<UUID> assetIds) {
        List<MediaAsset> assets = findAssets(assetIds);
        for (MediaAsset asset : assets) {
            deleteObjects(asset);
        }
        mediaAssetRepository.deleteAll(assets);
        return new MediaDtos.DeleteMediaResponse(assets.size());
    }

    @Transactional(readOnly = true)
    public void writeDownloadZip(List<UUID> assetIds, OutputStream outputStream) throws IOException {
        List<MediaAsset> assets = findAssets(assetIds);
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            for (MediaAsset asset : assets) {
                ZipEntry zipEntry = new ZipEntry(uniqueZipName(asset, usedNames));
                zipOutputStream.putNextEntry(zipEntry);
                try (ResponseInputStream<GetObjectResponse> objectStream = s3Client.getObject(GetObjectRequest.builder()
                        .bucket(storageProperties.bucket())
                        .key(asset.getOriginalObjectKey())
                        .build())) {
                    objectStream.transferTo(zipOutputStream);
                }
                zipOutputStream.closeEntry();
            }
        }
    }

    private String createInlineUrl(String objectKey, String contentType, String filename) {
        Duration ttl = storageProperties.inlineUrlTtl();
        return createInlineUrl(objectKey, contentType, filename, ttl, privateCacheControl(ttl, true));
    }

    private String createInlineUrl(String objectKey, String contentType, String filename, Duration ttl, String responseCacheControl) {
        GetObjectRequest.Builder requestBuilder = GetObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(objectKey)
                .responseContentType(contentType)
                .responseContentDisposition("inline; filename=\"" + filename + "\"");
        if (responseCacheControl != null) {
            requestBuilder.responseCacheControl(responseCacheControl);
        }
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(requestBuilder.build())
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    private static String privateCacheControl(Duration ttl, boolean immutable) {
        return "private, max-age=" + ttl.toSeconds() + (immutable ? ", immutable" : "");
    }

    private List<MediaAsset> findAssets(List<UUID> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            throw new IllegalArgumentException("At least one media asset is required");
        }
        List<MediaAsset> assets = new ArrayList<>(mediaAssetRepository.findAllById(assetIds));
        if (assets.size() != Set.copyOf(assetIds).size()) {
            throw new IllegalArgumentException("Some media assets were not found");
        }
        return assets;
    }


    private boolean enqueueWebAssetGeneration(UUID assetId) {
        if (!queuedProcessingAssetIds.add(assetId)) {
            return false;
        }
        Runnable task = () -> mediaProcessingExecutor.execute(() -> processQueuedWebAssetGeneration(assetId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        queuedProcessingAssetIds.remove(assetId);
                    }
                }
            });
            return true;
        }
        task.run();
        return true;
    }

    private void processQueuedWebAssetGeneration(UUID assetId) {
        try {
            MediaAsset asset = mediaAssetRepository.findById(assetId).orElse(null);
            if (asset == null || asset.getUploadStatus() != UploadStatus.UPLOADED) {
                return;
            }
            generateWebAssetsFromStorage(asset);
            mediaAssetRepository.save(asset);
        } catch (RuntimeException exception) {
            log.warn("Queued web asset generation failed for {}", assetId, exception);
        } finally {
            queuedProcessingAssetIds.remove(assetId);
        }
    }

    private void deleteObjects(MediaAsset asset) {
        deleteObject(asset.getOriginalObjectKey());
        if (asset.hasThumbnail()) {
            deleteObject(asset.getThumbnailObjectKey());
        }
        if (asset.hasPreview()) {
            deleteObject(asset.getPreviewObjectKey());
        }
    }

    private void deleteObject(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(objectKey)
                .build());
    }

    private boolean needsWebAssetGeneration(MediaAsset asset) {
        if (asset.getMediaType() == MediaType.VIDEO) {
            return !asset.hasPreview() || !asset.hasThumbnail();
        }
        return !asset.hasThumbnail() || (isHeifImage(asset) && !asset.hasPreview());
    }

    private boolean needsPreviewGeneration(MediaAsset asset) {
        return asset.getMediaType() == MediaType.VIDEO || isHeifImage(asset);
    }

    private void generateWebAssets(MediaAsset asset, Path originalFile) {
        if (originalFile == null) {
            return;
        }
        if (asset.getMediaType() == MediaType.VIDEO) {
            generateThumbnailIfVideo(asset, originalFile);
            generateVideoPreviewIfNeeded(asset, originalFile);
            return;
        }
        if (isHeifImage(asset)) {
            generateImagePreviewIfNeeded(asset, originalFile);
        }
        generateImageThumbnailIfNeeded(asset, originalFile);
    }

    private void generateImagePreviewIfNeeded(MediaAsset asset, Path originalFile) {
        if (asset.hasPreview()) {
            return;
        }
        Path previewFile = null;
        try {
            previewFile = Files.createTempFile("familyalbum-image-preview-", ".jpg");
            int exitCode = runFfmpeg(List.of(
                    "ffmpeg",
                    "-y",
                    "-i", originalFile.toString(),
                    "-frames:v", "1",
                    "-vf", "scale=2048:2048:force_original_aspect_ratio=decrease",
                    "-q:v", "3",
                    previewFile.toString()
            ));
            if (exitCode != 0 || Files.size(previewFile) == 0) {
                log.warn("HEIF image preview generation failed for asset {} with exit code {}", asset.getId(), exitCode);
                return;
            }
            String previewObjectKey = createPreviewObjectKey(".jpg");
            uploadGeneratedObject(previewObjectKey, "image/jpeg", previewFile);
            asset.setPreview(previewObjectKey, "image/jpeg");
        } catch (IOException exception) {
            log.warn("Failed to generate HEIF image preview for {}", asset.getId(), exception);
        } finally {
            deleteTempFile(previewFile);
        }
    }

    private void generateImageThumbnailIfNeeded(MediaAsset asset, Path originalFile) {
        if (asset.hasThumbnail()) {
            return;
        }
        Path thumbnailFile = null;
        try {
            thumbnailFile = Files.createTempFile("familyalbum-image-thumbnail-", ".jpg");
            int exitCode = runFfmpeg(List.of(
                    "ffmpeg",
                    "-y",
                    "-i", originalFile.toString(),
                    "-frames:v", "1",
                    "-vf", "scale=384:384:force_original_aspect_ratio=decrease",
                    "-q:v", "4",
                    thumbnailFile.toString()
            ));
            if (exitCode != 0 || Files.size(thumbnailFile) == 0) {
                log.warn("Image thumbnail generation failed for asset {} with exit code {}", asset.getId(), exitCode);
                return;
            }
            String thumbnailObjectKey = createThumbnailObjectKey();
            uploadGeneratedObject(thumbnailObjectKey, "image/jpeg", thumbnailFile);
            asset.setThumbnailObjectKey(thumbnailObjectKey);
        } catch (IOException exception) {
            log.warn("Failed to generate image thumbnail for {}", asset.getId(), exception);
        } finally {
            deleteTempFile(thumbnailFile);
        }
    }

    private void generateVideoPreviewIfNeeded(MediaAsset asset, Path originalFile) {
        if (asset.hasPreview()) {
            return;
        }
        Path previewFile = null;
        try {
            previewFile = Files.createTempFile("familyalbum-video-preview-", ".mp4");
            int exitCode = runFfmpeg(List.of(
                    "ffmpeg",
                    "-y",
                    "-i", originalFile.toString(),
                    "-map", "0:v:0",
                    "-map", "0:a?",
                    "-vf", "scale=1920:1080:force_original_aspect_ratio=decrease:force_divisible_by=2",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "23",
                    "-pix_fmt", "yuv420p",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-movflags", "+faststart",
                    previewFile.toString()
            ));
            if (exitCode != 0 || Files.size(previewFile) == 0) {
                log.warn("Video preview generation failed for asset {} with exit code {}", asset.getId(), exitCode);
                return;
            }
            String previewObjectKey = createPreviewObjectKey(".mp4");
            uploadGeneratedObject(previewObjectKey, "video/mp4", previewFile);
            asset.setPreview(previewObjectKey, "video/mp4");
        } catch (IOException exception) {
            log.warn("Failed to generate video preview for {}", asset.getId(), exception);
        } finally {
            deleteTempFile(previewFile);
        }
    }

    private void uploadGeneratedObject(String objectKey, String contentType, Path file) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(storageProperties.bucket())
                        .key(objectKey)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromFile(file)
        );
    }

    private int runFfmpeg(List<String> command) throws IOException {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start();
            String stderr = compactLogLine(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            int exitCode = process.waitFor();
            if (exitCode != 0 && !stderr.isBlank()) {
                log.warn("ffmpeg failed with exit code {}. stderr: {}", exitCode, abbreviate(stderr, 1600));
            }
            return exitCode;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static String compactLogLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private void generateWebAssetsFromStorage(MediaAsset asset) {
        if (!needsWebAssetGeneration(asset)) {
            return;
        }
        Path originalFile = null;
        try {
            originalFile = Files.createTempFile("familyalbum-original-", extensionOf(asset.getOriginalFilename()));
            try (ResponseInputStream<GetObjectResponse> objectStream = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(storageProperties.bucket())
                    .key(asset.getOriginalObjectKey())
                    .build())) {
                Files.copy(objectStream, originalFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            generateWebAssets(asset, originalFile);
        } catch (IOException exception) {
            log.warn("Failed to prepare web media source for {}", asset.getId(), exception);
        } finally {
            deleteTempFile(originalFile);
        }
    }

    private void generateThumbnailIfVideo(MediaAsset asset, Path originalFile) {
        if (asset.getMediaType() != MediaType.VIDEO || originalFile == null || asset.hasThumbnail()) {
            return;
        }
        Path thumbnailFile = null;
        try {
            thumbnailFile = Files.createTempFile("familyalbum-thumbnail-", ".jpg");
            int exitCode = runFfmpeg(List.of(
                    "ffmpeg",
                    "-y",
                    "-ss", "00:00:01",
                    "-i", originalFile.toString(),
                    "-frames:v", "1",
                    "-vf", "scale=384:-2",
                    "-q:v", "4",
                    thumbnailFile.toString()
            ));
            if (exitCode != 0 || Files.size(thumbnailFile) == 0) {
                log.warn("ffmpeg thumbnail generation failed for asset {} with exit code {}", asset.getId(), exitCode);
                return;
            }
            String thumbnailObjectKey = createThumbnailObjectKey();
            uploadGeneratedObject(thumbnailObjectKey, "image/jpeg", thumbnailFile);
            asset.setThumbnailObjectKey(thumbnailObjectKey);
        } catch (IOException exception) {
            log.warn("Failed to generate video thumbnail for {}", asset.getId(), exception);
        } finally {
            deleteTempFile(thumbnailFile);
        }
    }

    private void verifyUploadedObject(MediaAsset asset) {
        try {
            var response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(storageProperties.bucket())
                    .key(asset.getOriginalObjectKey())
                    .build());
            if (response.contentLength() != asset.getByteSize()) {
                asset.markFailed();
                throw new IllegalStateException("Uploaded object size does not match expected file size");
            }
        } catch (NoSuchKeyException exception) {
            asset.markFailed();
            throw new IllegalStateException("Uploaded object does not exist in storage", exception);
        }
    }

    private static String sha256Hex(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream stream = new DigestInputStream(Files.newInputStream(file), digest)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to hash uploaded file", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String createOriginalObjectKey(String filename) {
        return "originals/%s/%s%s".formatted(
                Instant.now().toString().substring(0, 10).replace("-", "/"),
                UUID.randomUUID(),
                extensionOf(filename)
        );
    }

    private static String createThumbnailObjectKey() {
        return "thumbnails/%s/%s.jpg".formatted(
                Instant.now().toString().substring(0, 10).replace("-", "/"),
                UUID.randomUUID()
        );
    }

    private static String createPreviewObjectKey(String extension) {
        return "previews/%s/%s%s".formatted(
                Instant.now().toString().substring(0, 10).replace("-", "/"),
                UUID.randomUUID(),
                extension
        );
    }

    private static MediaType detectMediaType(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("image/")) {
            return MediaType.IMAGE;
        }
        if (lower.startsWith("video/")) {
            return MediaType.VIDEO;
        }
        throw new IllegalArgumentException("Only image and video files are supported");
    }

    private static String normalizeContentType(String contentType, String filename) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();
        if (!normalized.isBlank() && !"application/octet-stream".equals(normalized)) {
            return normalized;
        }
        return switch (extensionOf(filename).toLowerCase(Locale.ROOT)) {
            case ".heic", ".heics" -> "image/heic";
            case ".heif", ".heifs" -> "image/heif";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".mov" -> "video/quicktime";
            case ".m4v" -> "video/x-m4v";
            case ".mp4" -> "video/mp4";
            default -> "application/octet-stream";
        };
    }

    private static String cleanFilename(String filename) {
        String cleaned = StringUtils.cleanPath(filename == null ? "" : filename).replace("\"", "");
        return cleaned.isBlank() ? "upload" : cleaned;
    }

    private static String uniqueZipName(MediaAsset asset, Set<String> usedNames) {
        String filename = cleanFilename(asset.getOriginalFilename());
        String candidate = filename;
        int counter = 2;
        while (!usedNames.add(candidate)) {
            candidate = stemOf(filename) + "-" + counter + extensionOf(filename);
            counter++;
        }
        return candidate;
    }

    private static String stemOf(String filename) {
        String cleaned = cleanFilename(filename);
        int dotIndex = cleaned.lastIndexOf('.');
        if (dotIndex <= 0) {
            return cleaned;
        }
        return cleaned.substring(0, dotIndex);
    }

    private static boolean isHeifImage(MediaAsset asset) {
        String contentType = asset.getContentType().toLowerCase(Locale.ROOT);
        String extension = extensionOf(asset.getOriginalFilename());
        return contentType.equals("image/heic")
                || contentType.equals("image/heif")
                || contentType.equals("image/heic-sequence")
                || contentType.equals("image/heif-sequence")
                || extension.equals(".heic")
                || extension.equals(".heif")
                || extension.equals(".heics")
                || extension.equals(".heifs");
    }

    private static String previewFilename(MediaAsset asset) {
        if ("video/mp4".equals(asset.getPreviewContentType())) {
            return stemOf(asset.getOriginalFilename()) + ".mp4";
        }
        return stemOf(asset.getOriginalFilename()) + ".jpg";
    }
    private static String extensionOf(String filename) {
        String cleaned = cleanFilename(filename);
        int dotIndex = cleaned.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == cleaned.length() - 1) {
            return "";
        }
        return cleaned.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    private static void deleteTempFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Failed to delete temp file {}", path, exception);
        }
    }
}
