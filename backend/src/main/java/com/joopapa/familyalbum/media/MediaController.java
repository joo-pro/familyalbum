package com.joopapa.familyalbum.media;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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

    @PostMapping("/media/upload")
    MediaDtos.MediaAssetResponse uploadMedia(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "capturedAt", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant capturedAt
    ) {
        return mediaService.uploadFile(file, capturedAt);
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

    @GetMapping("/media/{assetId}/file")
    StreamingResponseBody downloadMediaFile(@PathVariable UUID assetId, HttpServletResponse response) {
        MediaAsset asset = mediaService.getAsset(assetId);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(asset.getOriginalFilename()));
        response.setContentType(asset.getContentType());
        return outputStream -> mediaService.writeOriginalFile(assetId, outputStream);
    }

    @PostMapping(value = "/media/download", produces = "application/zip")
    StreamingResponseBody downloadMediaZip(@Valid @RequestBody MediaDtos.BatchMediaRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"familyalbum-media.zip\"");
        response.setContentType("application/zip");
        return outputStream -> mediaService.writeDownloadZip(request.assetIds(), outputStream);
    }

    @GetMapping("/media/{assetId}/view")
    RedirectView viewMedia(@PathVariable UUID assetId) {
        RedirectView redirectView = new RedirectView(mediaService.createViewUrl(assetId));
        redirectView.setExposeModelAttributes(false);
        return redirectView;
    }

    @GetMapping("/media/{assetId}/thumbnail")
    ResponseEntity<StreamingResponseBody> viewThumbnail(@PathVariable UUID assetId, WebRequest webRequest) {
        MediaService.ThumbnailContent content = mediaService.resolveThumbnailContent(assetId);
        String etag = "\"" + content.etag() + "\"";
        if (webRequest.checkNotModified(etag)) {
            return null;
        }

        CacheControl cacheControl = content.settled()
                ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
                : CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

        StreamingResponseBody body = outputStream -> mediaService.writeObject(content.objectKey(), outputStream);
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(cacheControl)
                .contentType(org.springframework.http.MediaType.parseMediaType(content.contentType()))
                .body(body);
    }

    @DeleteMapping("/media/{assetId}")
    MediaDtos.DeleteMediaResponse deleteMedia(@PathVariable UUID assetId) {
        return mediaService.deleteAsset(assetId);
    }

    @PostMapping("/media/delete")
    MediaDtos.DeleteMediaResponse deleteMediaBatch(@Valid @RequestBody MediaDtos.BatchMediaRequest request) {
        return mediaService.deleteAssets(request.assetIds());
    }

    private static String contentDisposition(String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"download\"; filename*=UTF-8''" + encoded;
    }
}