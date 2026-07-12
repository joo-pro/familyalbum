package com.joopapa.familyalbum.media;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class MediaDtos {
    private MediaDtos() {
    }

    public record CreateUploadUrlRequest(
            @NotBlank String filename,
            @NotBlank String contentType,
            @Min(1) long byteSize,
            Instant capturedAt
    ) {
    }

    public record CreateUploadUrlResponse(
            UUID assetId,
            String objectKey,
            String uploadUrl,
            Instant expiresAt
    ) {
    }

    public record CompleteUploadRequest(@NotNull UUID assetId) {
    }

    public record DownloadUrlResponse(String downloadUrl, Instant expiresAt) {
    }

    public record MediaAssetResponse(
            UUID id,
            String filename,
            String contentType,
            MediaType mediaType,
            long byteSize,
            UploadStatus uploadStatus,
            Instant capturedAt,
            Instant createdAt
    ) {
        static MediaAssetResponse from(MediaAsset asset) {
            return new MediaAssetResponse(
                    asset.getId(),
                    asset.getOriginalFilename(),
                    asset.getContentType(),
                    asset.getMediaType(),
                    asset.getByteSize(),
                    asset.getUploadStatus(),
                    asset.getCapturedAt(),
                    asset.getCreatedAt()
            );
        }
    }
}