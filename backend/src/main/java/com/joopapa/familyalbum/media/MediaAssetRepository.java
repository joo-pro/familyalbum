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
            where :cursorDate is null
                or coalesce(asset.capturedAt, asset.createdAt) < :cursorDate
                or (coalesce(asset.capturedAt, asset.createdAt) = :cursorDate and asset.createdAt < :cursorCreatedAt)
            order by coalesce(asset.capturedAt, asset.createdAt) desc, asset.createdAt desc
            """)
    List<MediaAsset> findTimelinePage(
            @Param("cursorDate") Instant cursorDate,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            Pageable pageable
    );

    Optional<MediaAsset> findFirstByContentHashAndUploadStatus(String contentHash, UploadStatus uploadStatus);
}