package com.joopapa.familyalbum.media;

import com.joopapa.familyalbum.auth.FamilyUserRole;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MediaDtos {
    private MediaDtos() {
    }

    public record CreateUploadUrlRequest(
            @NotBlank String filename,
            @NotBlank String contentType,
            @Min(1) long byteSize,
            Instant capturedAt,
            MediaVisibility visibility
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

    public record BatchMediaRequest(@NotNull List<@NotNull UUID> assetIds) {
    }

    public record DeleteMediaResponse(int deletedCount) {
    }

    public record UpdateVisibilityRequest(
            @NotNull List<@NotNull UUID> assetIds,
            @NotNull MediaVisibility visibility
    ) {
    }

    public record UpdateVisibilityResponse(int updatedCount) {
    }

    public record BackfillMediaResponse(
            int candidateCount,
            int queuedCount,
            int alreadyQueuedCount,
            int thumbnailMissingCount,
            int previewMissingCount
    ) {
    }

    public record MediaPageResponse(
            List<MediaAssetResponse> items,
            String nextCursor,
            boolean hasMore
    ) {
    }

    public record MediaAssetResponse(
            UUID id,
            String filename,
            String contentType,
            MediaType mediaType,
            long byteSize,
            UploadStatus uploadStatus,
            Instant capturedAt,
            Instant createdAt,
            boolean hasThumbnail,
            MediaVisibility visibility,
            FamilyUserRole uploadedByRole,
            boolean duplicate
    ) {
        static MediaAssetResponse from(MediaAsset asset) {
            return from(asset, false);
        }

        static MediaAssetResponse from(MediaAsset asset, boolean duplicate) {
            return new MediaAssetResponse(
                    asset.getId(),
                    asset.getOriginalFilename(),
                    asset.getContentType(),
                    asset.getMediaType(),
                    asset.getByteSize(),
                    asset.getUploadStatus(),
                    asset.getCapturedAt(),
                    asset.getCreatedAt(),
                    asset.hasThumbnail(),
                    asset.getVisibility(),
                    asset.getUploadedByRole(),
                    duplicate
            );
        }
    }
}