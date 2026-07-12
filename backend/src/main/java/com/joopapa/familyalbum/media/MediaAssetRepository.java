package com.joopapa.familyalbum.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    @Query("""
            select asset
            from MediaAsset asset
            order by coalesce(asset.capturedAt, asset.createdAt) desc, asset.createdAt desc
            """)
    List<MediaAsset> findTimeline();
}