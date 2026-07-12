package com.joopapa.familyalbum.media;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    MediaDtos.MediaPageResponse listMedia(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "48") int limit
    ) {
        return mediaService.listAssetsPage(cursor, limit);
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
    ResponseEntity<StreamingResponseBody> viewThumbnail(
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        MediaService.CachedMediaObject thumbnail = mediaService.getThumbnailObject(assetId);
        HttpHeaders headers = cacheHeaders(thumbnail);
        if (etagMatches(ifNoneMatch, thumbnail.eTag())) {
            return ResponseEntity.status(304).headers(headers).build();
        }
        return ResponseEntity.ok()
                .headers(headers)
                .body(outputStream -> {
                    if (thumbnail.hasInlineContent()) {
                        outputStream.write(thumbnail.inlineContent());
                        return;
                    }
                    mediaService.writeObject(thumbnail.objectKey(), outputStream);
                });
    }

    @DeleteMapping("/media/{assetId}")
    MediaDtos.DeleteMediaResponse deleteMedia(@PathVariable UUID assetId) {
        return mediaService.deleteAsset(assetId);
    }

    @PostMapping("/media/delete")
    MediaDtos.DeleteMediaResponse deleteMediaBatch(@Valid @RequestBody MediaDtos.BatchMediaRequest request) {
        return mediaService.deleteAssets(request.assetIds());
    }

    private static HttpHeaders cacheHeaders(MediaService.CachedMediaObject mediaObject) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, mediaObject.contentType());
        headers.set(HttpHeaders.CONTENT_DISPOSITION, inlineDisposition(mediaObject.filename()));
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=" + mediaObject.browserCacheTtl().toSeconds() + ", immutable");
        headers.set(HttpHeaders.ETAG, mediaObject.eTag());
        headers.setLastModified(mediaObject.lastModified());
        return headers;
    }

    private static boolean etagMatches(String ifNoneMatch, String eTag) {
        return ifNoneMatch != null && ifNoneMatch.lines()
                .flatMap(line -> List.of(line.split(",")).stream())
                .map(String::trim)
                .anyMatch(candidate -> candidate.equals(eTag) || candidate.equals("*"));
    }

    private static String inlineDisposition(String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "inline; filename=\"media\"; filename*=UTF-8''" + encoded;
    }

    private static String contentDisposition(String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"download\"; filename*=UTF-8''" + encoded;
    }
}