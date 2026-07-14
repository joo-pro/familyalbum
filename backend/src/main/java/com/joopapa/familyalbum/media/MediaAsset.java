package com.joopapa.familyalbum.media;

import com.joopapa.familyalbum.auth.FamilyUserRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 1024)
    private String originalObjectKey;

    @Column(length = 1024)
    private String thumbnailObjectKey;

    @Column(length = 1024)
    private String previewObjectKey;

    @Column(length = 255)
    private String previewContentType;

    @Column(length = 64)
    private String contentHash;

    @Column(nullable = false, length = 512)
    private String originalFilename;

    @Column(nullable = false)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaType mediaType;

    @Column(nullable = false)
    private long byteSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadStatus uploadStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MediaVisibility visibility = MediaVisibility.FAMILY;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private FamilyUserRole uploadedByRole;

    private Instant capturedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected MediaAsset() {
    }

    public MediaAsset(String originalObjectKey, String originalFilename, String contentType, MediaType mediaType, long byteSize, Instant capturedAt, MediaVisibility visibility, FamilyUserRole uploadedByRole) {
        Instant now = Instant.now();
        this.originalObjectKey = originalObjectKey;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.mediaType = mediaType;
        this.byteSize = byteSize;
        this.capturedAt = capturedAt;
        this.visibility = visibility == null ? MediaVisibility.FAMILY : visibility;
        this.uploadedByRole = uploadedByRole;
        this.uploadStatus = UploadStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getOriginalObjectKey() {
        return originalObjectKey;
    }

    public String getThumbnailObjectKey() {
        return thumbnailObjectKey;
    }

    public String getPreviewObjectKey() {
        return previewObjectKey;
    }

    public String getPreviewContentType() {
        return previewContentType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public UploadStatus getUploadStatus() {
        return uploadStatus;
    }

    public MediaVisibility getVisibility() {
        return visibility;
    }

    public FamilyUserRole getUploadedByRole() {
        return uploadedByRole;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean hasThumbnail() {
        return thumbnailObjectKey != null && !thumbnailObjectKey.isBlank();
    }

    public boolean hasPreview() {
        return previewObjectKey != null && !previewObjectKey.isBlank();
    }

    public void setThumbnailObjectKey(String thumbnailObjectKey) {
        this.thumbnailObjectKey = thumbnailObjectKey;
        this.updatedAt = Instant.now();
    }

    public void setPreview(String previewObjectKey, String previewContentType) {
        this.previewObjectKey = previewObjectKey;
        this.previewContentType = previewContentType;
        this.updatedAt = Instant.now();
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
        this.updatedAt = Instant.now();
    }

    public void markUploaded() {
        this.uploadStatus = UploadStatus.UPLOADED;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.uploadStatus = UploadStatus.FAILED;
        this.updatedAt = Instant.now();
    }
}