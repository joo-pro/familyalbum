package com.joopapa.familyalbum.media;

import com.joopapa.familyalbum.storage.StorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class MediaService {
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
    public MediaDtos.CreateUploadUrlResponse createUploadUrl(MediaDtos.CreateUploadUrlRequest request) {
        MediaType mediaType = detectMediaType(request.contentType());
        String objectKey = "originals/%s/%s%s".formatted(
                Instant.now().toString().substring(0, 10).replace("-", "/"),
                UUID.randomUUID(),
                extensionOf(request.filename())
        );
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
        asset.markUploaded();
        return MediaDtos.MediaAssetResponse.from(asset);
    }

    @Transactional(readOnly = true)
    public MediaDtos.DownloadUrlResponse createDownloadUrl(UUID assetId) {
        MediaAsset asset = mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Media asset not found"));
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

    private static String cleanFilename(String filename) {
        String cleaned = StringUtils.cleanPath(filename).replace("\"", "");
        return cleaned.isBlank() ? "upload" : cleaned;
    }

    private static String extensionOf(String filename) {
        String cleaned = cleanFilename(filename);
        int dotIndex = cleaned.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == cleaned.length() - 1) {
            return "";
        }
        return cleaned.substring(dotIndex).toLowerCase(Locale.ROOT);
    }
}