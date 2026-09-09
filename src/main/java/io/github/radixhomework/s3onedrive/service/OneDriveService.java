package io.github.radixhomework.s3onedrive.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.radixhomework.s3onedrive.auth.OneDriveTokenService;
import io.github.radixhomework.s3onedrive.config.OneDriveProperties;
import io.github.radixhomework.s3onedrive.util.KeySanitizer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Microsoft Graph Drive API.
 *
 * Path convention:
 *   OneDrive path  =  /{rootFolder}/{bucket}/{objectKey}
 *
 * S3 keys are mapped onto OneDrive-safe names by {@link KeySanitizer}.
 * All methods are synchronous (block()) because the S3 controller layer
 * uses traditional Servlet I/O.
 */
@Slf4j
@Service
public class OneDriveService {

    /** Graph children pages fetched per folder (999 = documented max page size). */
    private static final int PAGE_SIZE = 999;

    /** Safety cap on pagination follow-ups per listing call. */
    private static final int MAX_PAGES = 100;

    private final WebClient graphClient;
    private final OneDriveTokenService tokenService;
    private final OneDriveProperties properties;

    public OneDriveService(WebClient graphWebClient,
                           OneDriveTokenService tokenService,
                           OneDriveProperties properties) {
        this.graphClient  = graphWebClient;
        this.tokenService = tokenService;
        this.properties   = properties;
    }

    // ── Drive base path ───────────────────────────────────────────────────────

    /** Returns the base Graph path for drive items (handles custom drive ID). */
    private String driveRoot() {
        if (properties.getDriveId() != null && !properties.getDriveId().isBlank()) {
            return "/drives/" + properties.getDriveId() + "/root";
        }
        return "/me/drive/root";
    }

    /** Builds a Graph item path like  /me/drive/root:/s3/bucket/key: */
    private String itemPath(String... segments) {
        StringBuilder sb = new StringBuilder(driveRoot()).append(":");
        sb.append("/").append(properties.getRootFolder());
        for (String seg : segments) {
            if (seg != null && !seg.isBlank()) {
                sb.append("/").append(KeySanitizer.sanitize(seg.replace("//", "/")));
            }
        }
        sb.append(":");
        return sb.toString();
    }

    private String bearer() {
        return "Bearer " + tokenService.getBearerToken();
    }

    /** Retries transient Graph throttling / availability errors with exponential backoff. */
    private <T> Mono<T> withRetry(Mono<T> mono) {
        return mono.retryWhen(Retry.backoff(4, Duration.ofMillis(500))
            .maxBackoff(Duration.ofSeconds(20))
            .filter(this::isRetryable));
    }

    private boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException wce) {
            int status = wce.getStatusCode().value();
            return status == 429 || status == 503 || status == 504;
        }
        return false;
    }

    // ── Folder / Bucket operations ────────────────────────────────────────────

    /**
     * Lists all direct-child folders under the root folder.
     * Each folder represents an S3 bucket.
     */
    public DriveItemList listRootChildren() {
        String url = driveRoot() + ":/" + properties.getRootFolder() + ":/children"
            + "?$select=name,id,createdDateTime,lastModifiedDateTime,folder&$top=" + PAGE_SIZE;

        return (DriveItemList) withRetry(graphClient.get()
            .uri(url)
            .header("Authorization", bearer())
            .retrieve()
            .bodyToMono(DriveItemList.class)).block();
    }

    /**
     * Lists every item under {@code bucket/folder} (non-recursive), following
     * Graph @odata.nextLink pagination until the folder is exhausted.
     *
     * @param folder relative folder path inside the bucket; empty = bucket root
     */
    public DriveItemList listBucketChildren(String bucket, String folder) {
        String base = itemPath(bucket, folder) + "/children"
            + "?$select=name,id,size,createdDateTime,lastModifiedDateTime,file,folder"
            + "&$top=" + PAGE_SIZE;

        DriveItemList merged = new DriveItemList();
        merged.setItems(new java.util.ArrayList<>());

        String url = base;
        for (int page = 0; url != null && page < MAX_PAGES; page++) {
            DriveItemList result = (DriveItemList) withRetry(graphClient.get()
                .uri(url)
                .header("Authorization", bearer())
                .retrieve()
                .bodyToMono(DriveItemList.class)).block();
            if (result == null) break;
            if (result.getItems() != null) merged.getItems().addAll(result.getItems());
            url = result.getNextLink();
        }
        return merged;
    }

    /**
     * Creates a folder. Silently ignores 409 Conflict (already exists).
     */
    public void createFolder(String... pathSegments) {
        // Build parent path (all but last segment)
        String folderName = pathSegments[pathSegments.length - 1];
        String parentPath;
        if (pathSegments.length == 1) {
            parentPath = driveRoot() + ":/" + properties.getRootFolder() + ":";
        } else {
            String[] parent = new String[pathSegments.length - 1];
            System.arraycopy(pathSegments, 0, parent, 0, parent.length);
            parentPath = itemPath(parent);
        }

        String url = parentPath + "/children";
        Map<String, Object> body = Map.of(
            "name", KeySanitizer.sanitize(folderName),
            "folder", Map.of(),
            "@microsoft.graph.conflictBehavior", "fail"
        );

        ((Mono<Void>) withRetry(graphClient.post()
            .uri(url)
            .header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .onStatus(status -> status.value() == 409, resp -> Mono.empty()) // ignore conflict
            .bodyToMono(Void.class))).block();

        log.debug("Folder created/exists: {}", url);
    }

    /**
     * Deletes an item (file or folder) by path.
     * Returns true if deleted, false if not found.
     */
    public boolean deleteItem(String bucket, String key) {
        String url = itemPath(bucket, key);
        try {
            ((Mono<Void>) withRetry(graphClient.delete()
                .uri(url)
                .header("Authorization", bearer())
                .retrieve()
                .bodyToMono(Void.class))).block();
            return true;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) return false;
            throw e;
        }
    }

    // ── Object operations ─────────────────────────────────────────────────────

    /**
     * Downloads an item, honouring an optional HTTP Range header (single range),
     * and returns the response entity with a streaming Flux body.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Flux<DataBuffer>> downloadItem(String bucket, String key, String range) {
        WebClient.RequestHeadersSpec<?> spec = graphClient.get()
            .uri(itemPath(bucket, key) + "/content")
            .header("Authorization", bearer());
        if (range != null && !range.isBlank()) {
            spec = (WebClient.RequestHeadersSpec<?>) spec.header("Range", range);
        }
        return (ResponseEntity<Flux<DataBuffer>>) withRetry(spec.retrieve().toEntityFlux(DataBuffer.class)).block();
    }

    /** Full (non-range) streaming download. */
    public Flux<DataBuffer> downloadItem(String bucket, String key) {
        return downloadItem(bucket, key, null).getBody();
    }

    /**
     * Gets metadata of a single item (size, ETag, last modified).
     */
    public DriveItem getItemMetadata(String bucket, String key) {
        String url = itemPath(bucket, key)
            + "?$select=id,name,size,eTag,createdDateTime,lastModifiedDateTime,file";
        try {
            return (DriveItem) withRetry(graphClient.get()
                .uri(url)
                .header("Authorization", bearer())
                .retrieve()
                .bodyToMono(DriveItem.class)).block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;
            throw e;
        }
    }

    /**
     * Uploads a small object (≤ 4 MB) via simple PUT.
     */
    public DriveItem uploadSmall(String bucket, String key,
                                 byte[] data, String contentType) {
        String url = itemPath(bucket, key) + "/content";
        return (DriveItem) withRetry(graphClient.put()
            .uri(url)
            .header("Authorization", bearer())
            .contentType(contentType != null && !contentType.isBlank()
                ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(data)
            .retrieve()
            .bodyToMono(DriveItem.class)).block();
    }

    /**
     * Creates a resumable (large file) upload session.
     * Returns the uploadUrl to PUT parts to.
     */
    public String createUploadSession(String bucket, String key, String contentType) {
        String url = itemPath(bucket, key) + "/createUploadSession";

        Map<String, Object> item = contentType != null
            ? Map.of("@microsoft.graph.conflictBehavior", "replace",
                     "name", key,
                     "fileSystemInfo", Map.of())
            : Map.of("@microsoft.graph.conflictBehavior", "replace");

        Map<String, Object> body = Map.of("item", item);

        UploadSession session = (UploadSession) withRetry(graphClient.post()
            .uri(url)
            .header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(UploadSession.class)).block();

        if (session == null || session.uploadUrl == null) {
            throw new IllegalStateException("Failed to create upload session for " + key);
        }
        log.debug("Upload session created for {}/{}", bucket, key);
        return session.uploadUrl;
    }

    /**
     * Uploads a single chunk to a resumable upload session URL.
     *
     * @param uploadUrl   The URL returned by createUploadSession
     * @param chunkData   Bytes of this chunk (length = chunkData.length)
     * @param rangeStart  Byte offset of the first byte in chunkData
     * @param totalSize   Total file size
     */
    public void uploadPart(String uploadUrl, byte[] chunkData,
                           long rangeStart, long totalSize) {
        long rangeEnd = rangeStart + chunkData.length - 1;
        String contentRange = "bytes " + rangeStart + "-" + rangeEnd + "/" + totalSize;

        ((Mono<Void>) withRetry(graphClient.put()
            .uri(uploadUrl)
            .header("Content-Range", contentRange)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(chunkData)
            .retrieve()
            // 200 = complete, 202 = more parts expected, both are OK
            .onStatus(HttpStatusCode::is4xxClientError, resp ->
                resp.bodyToMono(String.class).flatMap(body ->
                    Mono.error(new IllegalStateException("Upload part failed: " + body))))
            .bodyToMono(Void.class))).block();

        log.debug("Uploaded range {}", contentRange);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DriveItem {
        public String id;
        public String name;
        public Long size;
        public String eTag;
        public String createdDateTime;
        public String lastModifiedDateTime;
        public Object file;   // non-null if this is a file
        public Object folder; // non-null if this is a folder
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DriveItemList {
        @JsonProperty("value")
        public List<DriveItem> items;
        @JsonProperty("@odata.nextLink")
        public String nextLink;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class UploadSession {
        @JsonProperty("uploadUrl")
        String uploadUrl;
        @JsonProperty("expirationDateTime")
        String expirationDateTime;
    }
}
