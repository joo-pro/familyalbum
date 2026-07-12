package com.joopapa.familyalbum.media;

import com.joopapa.familyalbum.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaAssetRepository mediaAssetRepository;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties storageProperties;

    public MediaService(
            MediaAssetRepository mediaAssetRepository,
            S3Client s3Client,
            S3Presigner s3Presigner,
            StorageProperties storageProperties
    ) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.storageProperties = storageProperties;
    }

    @Transactional(readOnly = true)
    public List<MediaDtos.MediaAssetResponse> listAssets() {
        return mediaAssetRepository.findTimeline().stream()
                .map(MediaDtos.MediaAssetResponse::from)
                .toList();
    }

    @Transactional
    public MediaDtos.MediaAssetResponse uploadFile(MultipartFile file, Instant capturedAt) {
        String contentType = normalizeContentType(file.getContentType());
        MediaType mediaType = detectMediaType(contentType);
        String filename = cleanFilename(file.getOriginalFilename());
        String objectKey = createOriginalObjectKey(filename);

        MediaAsset asset = mediaAssetRepository.save(new MediaAsset(
                objectKey,
                filename,
                contentType,
                mediaType,
                file.getSize(),
                capturedAt
        ));

        Path uploadFile = null;
        try {
            uploadFile = Files.createTempFile("familyalbum-upload-", extensionOf(filename));
            file.transferTo(uploadFile);
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(storageProperties.bucket())
                            .key(objectKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromFile(uploadFile)
            );
            verifyUploadedObject(asset);
            generateThumbnailIfVideo(asset, uploadFile);
            asset.markUploaded();
            return MediaDtos.MediaAssetResponse.from(asset);
        } catch (IOException exception) {
            asset.markFailed();
            throw new IllegalStateException("Failed to read uploaded file", exception);
        } catch (RuntimeException exception) {
            asset.markFailed();
            throw exception;
        } finally {
            deleteTempFile(uploadFile);
        }
    }

    @Transactional
    public MediaDtos.CreateUploadUrlResponse createUploadUrl(MediaDtos.CreateUploadUrlRequest request) {
        MediaType mediaType = detectMediaType(request.contentType());
        String objectKey = createOriginalObjectKey(request.filename());
        MediaAsset asset = mediaAssetRepository.save(new MediaAsset(
                objectKey,
                cleanFilename(request.filename()),
                request.contentType(),
                mediaType,
                request.byteSize(),
                request.capturedAt()
        ));

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(objectKey)
                .contentType(request.contentType())
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
        generateThumbnailFromStorageIfVideo(asset);
        asset.markUploaded();
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
        return createInlineUrl(asset.getOriginalObjectKey(), asset.getContentType(), asset.getOriginalFilename());
    }

    @Transactional
    public String createThumbnailViewUrl(UUID assetId) {
        MediaAsset asset = getAsset(assetId);
        generateThumbnailFromStorageIfVideo(asset);
        if (!asset.hasThumbnail()) {
            return createViewUrl(assetId);
        }
        return createInlineUrl(asset.getThumbnailObjectKey(), "image/jpeg", stemOf(asset.getOriginalFilename()) + ".jpg");
    }

    @Transactional(readOnly = true)
    public void writeOriginalFile(UUID assetId, OutputStream outputStream) throws IOException {
        MediaAsset asset = getAsset(assetId);
        try (ResponseInputStream<GetObjectResponse> objectStream = s3Client.getObject(GetObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(asset.getOriginalObjectKey())
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
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(objectKey)
                .responseContentType(contentType)
                .responseContentDisposition("inline; filename=\"" + filename + "\"")
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(storageProperties.downloadUrlTtl())
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
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

    private void deleteObjects(MediaAsset asset) {
        deleteObject(asset.getOriginalObjectKey());
        if (asset.hasThumbnail()) {
            deleteObject(asset.getThumbnailObjectKey());
        }
    }

    private void deleteObject(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(objectKey)
                .build());
    }

    private void generateThumbnailFromStorageIfVideo(MediaAsset asset) {
        if (asset.getMediaType() != MediaType.VIDEO || asset.hasThumbnail()) {
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
            generateThumbnailIfVideo(asset, originalFile);
        } catch (IOException exception) {
            log.warn("Failed to prepare video thumbnail source for {}", asset.getId(), exception);
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
            Process process = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-ss", "00:00:01",
                    "-i", originalFile.toString(),
                    "-frames:v", "1",
                    "-vf", "scale=640:-2",
                    "-q:v", "4",
                    thumbnailFile.toString()
            ).redirectErrorStream(true).start();
            int exitCode = process.waitFor();
            if (exitCode != 0 || Files.size(thumbnailFile) == 0) {
                log.warn("ffmpeg thumbnail generation failed for asset {} with exit code {}", asset.getId(), exitCode);
                return;
            }
            String thumbnailObjectKey = createThumbnailObjectKey(asset.getOriginalFilename());
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(storageProperties.bucket())
                            .key(thumbnailObjectKey)
                            .contentType("image/jpeg")
                            .build(),
                    RequestBody.fromFile(thumbnailFile)
            );
            asset.setThumbnailObjectKey(thumbnailObjectKey);
        } catch (IOException exception) {
            log.warn("Failed to generate video thumbnail for {}", asset.getId(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Video thumbnail generation was interrupted for {}", asset.getId(), exception);
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

    private static String createOriginalObjectKey(String filename) {
        return "originals/%s/%s%s".formatted(
                Instant.now().toString().substring(0, 10).replace("-", "/"),
                UUID.randomUUID(),
                extensionOf(filename)
        );
    }

    private static String createThumbnailObjectKey(String filename) {
        return "thumbnails/%s/%s.jpg".formatted(
                Instant.now().toString().substring(0, 10).replace("-", "/"),
                UUID.randomUUID()
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

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        return contentType;
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