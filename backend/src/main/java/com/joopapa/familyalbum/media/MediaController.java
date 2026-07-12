package com.joopapa.familyalbum.media;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class MediaController {
    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/media")
    List<MediaDtos.MediaAssetResponse> listMedia() {
        return mediaService.listAssets();
    }

    @PostMapping("/media/upload-url")
    MediaDtos.CreateUploadUrlResponse createUploadUrl(@Valid @RequestBody MediaDtos.CreateUploadUrlRequest request) {
        return mediaService.createUploadUrl(request);
    }

    @PostMapping("/media/upload-complete")
    MediaDtos.MediaAssetResponse completeUpload(@Valid @RequestBody MediaDtos.CompleteUploadRequest request) {
        return mediaService.completeUpload(request.assetId());
    }

    @PostMapping("/media/{assetId}/download-url")
    MediaDtos.DownloadUrlResponse createDownloadUrl(@PathVariable UUID assetId) {
        return mediaService.createDownloadUrl(assetId);
    }
}
