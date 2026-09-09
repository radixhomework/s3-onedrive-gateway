package io.github.radixhomework.s3onedrive.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.radixhomework.s3onedrive.auth.OneDriveTokenService;
import io.github.radixhomework.s3onedrive.config.OneDriveProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Microsoft Graph Drive API.
 *
 * Path convention:
 *   OneDrive path  =  /{rootFolder}/{bucket}/{objectKey}
 *
 * All methods are synchronous (block()) because the S3 controller layer
 * uses traditional Servlet I/O. Switch to reactive if desired.
 */
@Slf4j
@Service
public class OneDriveService {

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

    /** Builds a Graph item path like  /me/drive/root:/s3-gateway/bucket/key: */
    private String itemPath(String... segments) {
        StringBuilder sb = new StringBuilder(driveRoot()).append(":");
        sb.append("/").append(properties.getRootFolder());
        for (String seg : segments) {
            if (seg != null && !seg.isBlank()) {
                sb.append("/").append(seg.replace("//", "/"));
            }
        }
        sb.append(":");
        return sb.toString();
    }

    private String bearer() {
        return "Bearer " + tokenService.getBearerToken();
    }

    // ── Folder / Bucket operations ────────────────────────────────────────────

    /**
     * Lists all direct-child folders under the root folder.
     * Each folder represents an S3 bucket.
     */
    public DriveItemList listRootChildren() {
        String url = driveRoot() + ":/" + properties.getRootFolder() + ":/children"
            + "?$select=name,id,createdDateTime,lastModifiedDateTime,folder";

        return graphClient.get()
            .uri(url)
            .header("Authorization", bearer())
            .retrieve()
            .bodyToMono(DriveItemList.class)
            .block();
    }

    /**
     * Lists items directly under bucket (non-recursive, page-aware via nextLink).
     */
    public DriveItemList listBucketChildren(String bucket, String prefix, String delimiter) {
        String base = itemPath(bucket) + "/children"
            + "?$select=name,id,size,createdDateTime,lastModifiedDateTime,file,folder"
            + "&$top=1000";

        return graphClient.get()
            .uri(base)
            .header("Authorization", bearer())
            .retrieve()
            .bodyToMono(DriveItemList.class)
            .block();
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
            "name", folderName,
            "folder", Map.of(),
            "@microsoft.graph.conflictBehavior", "fail"
        );

        graphClient.post()
            .uri(url)
            .header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .onStatus(status -> status.value() == 409, resp -> Mono.empty()) // ignore conflict
            .bodyToMono(Void.class)
            .block();

        log.debug("Folder created/exists: {}", url);
    }

    /**
     * Deletes an item (file or folder) by path.
     * Returns true if deleted, false if not found.
     */
    public boolean deleteItem(String bucket, String key) {
        String url = itemPath(bucket, key);
        try {
            graphClient.delete()
                .uri(url)
                .header("Authorization", bearer())
                .retrieve()
                .bodyToMono(Void.class)
                .block();
            return true;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) return false;
            throw e;
        }
    }

    // ── Object operations ─────────────────────────────────────────────────────

    /**
     * Downloads a file and returns a streaming Flux of DataBuffers.
     * The caller is responsible for releasing the buffers.
     */
    public Flux<DataBuffer> downloadItem(String bucket, String key) {
        String url = itemPath(bucket, key) + "/content";
        return graphClient.get()
            .uri(url)
            .header("Authorization", bearer())
            .retrieve()
            .bodyToFlux(DataBuffer.class);
    }

    /**
     * Gets metadata of a single item (size, ETag, last modified).
     */
    public DriveItem getItemMetadata(String bucket, String key) {
        String url = itemPath(bucket, key)
            + "?$select=id,name,size,eTag,createdDateTime,lastModifiedDateTime,file";
        try {
            return graphClient.get()
                .uri(url)
                .header("Authorization", bearer())
                .retrieve()
                .bodyToMono(DriveItem.class)
                .block();
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
        return graphClient.put()
            .uri(url)
            .header("Authorization", bearer())
            .contentType(contentType != null
                ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(data)
            .retrieve()
            .bodyToMono(DriveItem.class)
            .block();
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

        UploadSession session = graphClient.post()
            .uri(url)
            .header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(UploadSession.class)
            .block();

        if (session == null || session.uploadUrl == null) {
            throw new IllegalStateException("Failed to create upload session for " + key);
        }
        log.debug("Upload session created for {}/{}: {}", bucket, key, session.uploadUrl);
        return session.uploadUrl;
    }

    /**
     * Uploads a single part to a resumable upload session URL.
     *
     * @param uploadUrl   The URL returned by createUploadSession
     * @param partData    Bytes of this part
     * @param rangeStart  Byte offset of the first byte in partData
     * @param totalSize   Total file size (-1 if unknown / streaming)
     */
    public void uploadPart(String uploadUrl, byte[] partData,
                           long rangeStart, long totalSize) {
        long rangeEnd = rangeStart + partData.length - 1;
        String contentRange = "bytes " + rangeStart + "-" + rangeEnd
            + "/" + (totalSize > 0 ? totalSize : "*");

        graphClient.put()
            .uri(uploadUrl)
            .header("Content-Range", contentRange)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(partData)
            .retrieve()
            // 200 = complete, 202 = more parts expected, both are OK
            .onStatus(HttpStatusCode::is4xxClientError, resp ->
                resp.bodyToMono(String.class).flatMap(body ->
                    Mono.error(new IllegalStateException("Upload part failed: " + body))))
            .bodyToMono(Void.class)
            .block();

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
