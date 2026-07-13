package com.joopapa.familyalbum.media;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    @Query("""
            select asset
            from MediaAsset asset
            order by coalesce(asset.capturedAt, asset.createdAt) desc, asset.createdAt desc
            """)
    List<MediaAsset> findTimeline();

    @Query("""
            select asset
            from MediaAsset asset
            order by coalesce(asset.capturedAt, asset.createdAt) desc, asset.createdAt desc
            """)
    List<MediaAsset> findTimelinePage(Pageable pageable);

    @Query("""
            select asset
            from MediaAsset asset
            where coalesce(asset.capturedAt, asset.createdAt) < :cursorDate
                or (coalesce(asset.capturedAt, asset.createdAt) = :cursorDate and asset.createdAt < :cursorCreatedAt)
            order by coalesce(asset.capturedAt, asset.createdAt) desc, asset.createdAt desc
            """)
    List<MediaAsset> findTimelinePageAfter(
            @Param("cursorDate") Instant cursorDate,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            Pageable pageable
    );
    @Query("""
            select asset
            from MediaAsset asset
            where asset.uploadStatus = :uploadStatus
                and (
                    (asset.mediaType = :videoType
                        and (asset.thumbnailObjectKey is null or asset.thumbnailObjectKey = '' or asset.previewObjectKey is null or asset.previewObjectKey = ''))
                    or (asset.mediaType = :imageType
                        and (
                            asset.thumbnailObjectKey is null
                            or asset.thumbnailObjectKey = ''
                            or ((lower(asset.contentType) in ('image/heic', 'image/heif', 'image/heic-sequence', 'image/heif-sequence')
                                or lower(asset.originalFilename) like '%.heic'
                                or lower(asset.originalFilename) like '%.heif'
                                or lower(asset.originalFilename) like '%.heics'
                                or lower(asset.originalFilename) like '%.heifs')
                                and (asset.previewObjectKey is null or asset.previewObjectKey = ''))
                        ))
                )
            order by asset.createdAt asc
            """)
    List<MediaAsset> findWebAssetBackfillCandidates(
            @Param("uploadStatus") UploadStatus uploadStatus,
            @Param("videoType") MediaType videoType,
            @Param("imageType") MediaType imageType,
            Pageable pageable
    );

    Optional<MediaAsset> findFirstByContentHashAndUploadStatus(String contentHash, UploadStatus uploadStatus);
}
