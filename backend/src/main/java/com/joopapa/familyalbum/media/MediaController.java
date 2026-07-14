package com.joopapa.familyalbum.media;

import com.joopapa.familyalbum.auth.FamilyUser;
import com.joopapa.familyalbum.auth.FamilyUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.view.RedirectView;

import java.net.InetAddress;
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
    private final FamilyUserService familyUserService;

    public MediaController(MediaService mediaService, FamilyUserService familyUserService) {
        this.mediaService = mediaService;
        this.familyUserService = familyUserService;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/media")
    MediaDtos.MediaPageResponse listMedia(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "48") int limit,
            Authentication authentication
    ) {
        return mediaService.listAssetsPage(cursor, limit, currentUser(authentication));
    }

    @PostMapping("/media/upload")
    MediaDtos.MediaAssetResponse uploadMedia(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "capturedAt", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant capturedAt,
            @RequestParam(value = "visibility", defaultValue = "FAMILY") MediaVisibility visibility,
            Authentication authentication
    ) {
        return mediaService.uploadFile(file, capturedAt, visibility, currentUser(authentication));
    }

    @PostMapping("/media/upload-url")
    MediaDtos.CreateUploadUrlResponse createUploadUrl(
            @Valid @RequestBody MediaDtos.CreateUploadUrlRequest request,
            Authentication authentication
    ) {
        return mediaService.createUploadUrl(request, currentUser(authentication));
    }

    @PostMapping("/media/upload-complete")
    MediaDtos.MediaAssetResponse completeUpload(
            @Valid @RequestBody MediaDtos.CompleteUploadRequest request,
            Authentication authentication
    ) {
        return mediaService.completeUpload(request.assetId(), currentUser(authentication));
    }

    @PostMapping("/media/{assetId}/download-url")
    MediaDtos.DownloadUrlResponse createDownloadUrl(@PathVariable UUID assetId, Authentication authentication) {
        return mediaService.createDownloadUrl(assetId, currentUser(authentication));
    }

    @GetMapping("/media/{assetId}/file")
    StreamingResponseBody downloadMediaFile(@PathVariable UUID assetId, Authentication authentication, HttpServletResponse response) {
        FamilyUser viewer = currentUser(authentication);
        MediaAsset asset = mediaService.getVisibleAssetForDownload(assetId, viewer);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(asset.getOriginalFilename()));
        response.setContentType(asset.getContentType());
        return outputStream -> mediaService.writeOriginalFile(assetId, viewer, outputStream);
    }

    @PostMapping(value = "/media/download", produces = "application/zip")
    StreamingResponseBody downloadMediaZip(
            @Valid @RequestBody MediaDtos.BatchMediaRequest request,
            Authentication authentication,
            HttpServletResponse response
    ) {
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"familyalbum-media.zip\"");
        response.setContentType("application/zip");
        return outputStream -> mediaService.writeDownloadZip(request.assetIds(), currentUser(authentication), outputStream);
    }

    @GetMapping("/media/{assetId}/view")
    RedirectView viewMedia(@PathVariable UUID assetId, Authentication authentication) {
        RedirectView redirectView = new RedirectView(mediaService.createViewUrl(assetId, currentUser(authentication)));
        redirectView.setExposeModelAttributes(false);
        return redirectView;
    }

    @GetMapping("/media/{assetId}/thumbnail")
    ResponseEntity<StreamingResponseBody> viewThumbnail(
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Authentication authentication
    ) {
        MediaService.CachedMediaObject thumbnail = mediaService.getThumbnailObject(assetId, currentUser(authentication));
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

    @PostMapping("/admin/media/backfill-web-assets")
    MediaDtos.BackfillMediaResponse backfillWebAssets(
            HttpServletRequest request,
            @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        verifyLocalAdminRequest(request);
        return mediaService.enqueueMissingWebAssets(limit);
    }

    @DeleteMapping("/media/{assetId}")
    MediaDtos.DeleteMediaResponse deleteMedia(@PathVariable UUID assetId, Authentication authentication) {
        return mediaService.deleteAsset(assetId, currentUser(authentication));
    }


    @PostMapping("/media/visibility")
    MediaDtos.UpdateVisibilityResponse updateVisibility(
            @Valid @RequestBody MediaDtos.UpdateVisibilityRequest request,
            Authentication authentication
    ) {
        return mediaService.updateVisibility(request.assetIds(), request.visibility(), currentUser(authentication));
    }
    @PostMapping("/media/delete")
    MediaDtos.DeleteMediaResponse deleteMediaBatch(
            @Valid @RequestBody MediaDtos.BatchMediaRequest request,
            Authentication authentication
    ) {
        return mediaService.deleteAssets(request.assetIds(), currentUser(authentication));
    }

    private FamilyUser currentUser(Authentication authentication) {
        return familyUserService.requireCurrentUser(authentication);
    }

    private static void verifyLocalAdminRequest(HttpServletRequest request) {
        if (StringUtils.hasText(request.getHeader("X-Forwarded-For")) || StringUtils.hasText(request.getHeader("X-Real-IP"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            InetAddress remoteAddress = InetAddress.getByName(request.getRemoteAddr());
            if (!remoteAddress.isLoopbackAddress() && !remoteAddress.isSiteLocalAddress()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (java.net.UnknownHostException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
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