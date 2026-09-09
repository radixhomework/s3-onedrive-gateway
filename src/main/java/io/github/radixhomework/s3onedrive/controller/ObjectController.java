package io.github.radixhomework.s3onedrive.controller;

import io.github.radixhomework.s3onedrive.model.S3Xml;
import io.github.radixhomework.s3onedrive.multipart.MultipartUploadStore;
import io.github.radixhomework.s3onedrive.multipart.MultipartUploadStore.MultipartUpload;
import io.github.radixhomework.s3onedrive.multipart.MultipartUploadStore.Part;
import io.github.radixhomework.s3onedrive.service.OneDriveService;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItem;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItemList;
import io.github.radixhomework.s3onedrive.util.XmlUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles S3 object-level requests:
 *
 *   GET    /{bucket}           → ListObjects (V1 & V2)
 *   GET    /{bucket}/{key}     → GetObject
 *   PUT    /{bucket}/{key}     → PutObject
 *   DELETE /{bucket}/{key}     → DeleteObject
 *   HEAD   /{bucket}/{key}     → HeadObject
 *   POST   /{bucket}/{key}?uploads            → CreateMultipartUpload
 *   PUT    /{bucket}/{key}?partNumber=N&uploadId=X → UploadPart
 *   POST   /{bucket}/{key}?uploadId=X         → CompleteMultipartUpload
 *   DELETE /{bucket}/{key}?uploadId=X         → AbortMultipartUpload
 */
@Slf4j
@RestController
public class ObjectController {

    private static final int SIMPLE_UPLOAD_THRESHOLD = 4 * 1024 * 1024; // 4 MB

    private final OneDriveService driveService;
    private final MultipartUploadStore multipartStore;

    public ObjectController(OneDriveService driveService,
                            MultipartUploadStore multipartStore) {
        this.driveService  = driveService;
        this.multipartStore = multipartStore;
    }

    // ── GET /{bucket} – ListObjects ───────────────────────────────────────────

    @GetMapping(value = "/{bucket}", produces = MediaType.APPLICATION_XML_VALUE)
    public void listObjects(@PathVariable String bucket,
                            @RequestParam(defaultValue = "") String prefix,
                            @RequestParam(defaultValue = "") String delimiter,
                            @RequestParam(value = "max-keys", defaultValue = "1000") int maxKeys,
                            HttpServletResponse response) throws IOException {
        log.debug("ListObjects bucket={} prefix={} delimiter={}", bucket, prefix, delimiter);

        DriveItemList result = driveService.listBucketChildren(bucket, prefix, delimiter);

        List<S3Xml.S3Object> objects = new ArrayList<>();
        List<S3Xml.CommonPrefix> commonPrefixes = new ArrayList<>();
        Set<String> seenPrefixes = new HashSet<>();

        if (result != null && result.getItems() != null) {
            for (DriveItem item : result.getItems()) {
                String key = item.getName();

                // Apply prefix filter
                if (!prefix.isBlank() && !key.startsWith(prefix)) continue;

                // Handle delimiter (virtual directories)
                if (!delimiter.isBlank()) {
                    int delimPos = key.indexOf(delimiter, prefix.length());
                    if (delimPos >= 0) {
                        String cp = key.substring(0, delimPos + delimiter.length());
                        if (seenPrefixes.add(cp)) {
                            commonPrefixes.add(S3Xml.CommonPrefix.builder().prefix(cp).build());
                        }
                        continue;
                    }
                }

                if (item.getFile() != null) {
                    objects.add(S3Xml.S3Object.builder()
                        .key(key)
                        .lastModified(item.getLastModifiedDateTime())
                        .eTag(item.getETag() != null ? item.getETag() : "\"" + item.getId() + "\"")
                        .size(item.getSize() != null ? item.getSize() : 0L)
                        .storageClass("STANDARD")
                        .build());
                }
            }
        }

        S3Xml.ListObjectsResult xml = S3Xml.ListObjectsResult.builder()
            .name(bucket)
            .prefix(prefix)
            .delimiter(delimiter.isBlank() ? null : delimiter)
            .maxKeys(maxKeys)
            .isTruncated(false)
            .contents(objects.isEmpty() ? null : objects)
            .commonPrefixes(commonPrefixes.isEmpty() ? null : commonPrefixes)
            .build();

        response.setStatus(200);
        response.setContentType(MediaType.APPLICATION_XML_VALUE);
        XmlUtil.writeXml(response.getOutputStream(), xml);
    }

    // ── GET /{bucket}/{key} – GetObject ───────────────────────────────────────

    @GetMapping("/{bucket}/**")
    public void getObject(@PathVariable String bucket,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        String key = extractKey(request, bucket);
        log.debug("GetObject {}/{}", bucket, key);

        // First fetch metadata to set correct headers
        DriveItem meta = driveService.getItemMetadata(bucket, key);
        if (meta == null) {
            sendError(response, 404, "NoSuchKey", "The specified key does not exist.", "/" + bucket + "/" + key);
            return;
        }

        response.setStatus(200);
        if (meta.getSize() != null) response.setContentLengthLong(meta.getSize());
        if (meta.getLastModifiedDateTime() != null)
            response.setHeader("Last-Modified", meta.getLastModifiedDateTime());
        if (meta.getETag() != null)
            response.setHeader("ETag", meta.getETag());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        // Stream content
        Flux<DataBuffer> content = driveService.downloadItem(bucket, key);
        try (OutputStream out = response.getOutputStream()) {
            content.toIterable().forEach(buf -> {
                try {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    out.write(bytes);
                    DataBufferUtils.release(buf);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            out.flush();
        }
    }

    // ── HEAD /{bucket}/{key} – HeadObject ─────────────────────────────────────

    @RequestMapping(method = RequestMethod.HEAD, value = "/{bucket}/**")
    public void headObject(@PathVariable String bucket,
                           HttpServletRequest request,
                           HttpServletResponse response) {
        String key = extractKey(request, bucket);
        log.debug("HeadObject {}/{}", bucket, key);

        DriveItem meta = driveService.getItemMetadata(bucket, key);
        if (meta == null) {
            response.setStatus(404);
            return;
        }

        response.setStatus(200);
        if (meta.getSize() != null) response.setContentLengthLong(meta.getSize());
        if (meta.getLastModifiedDateTime() != null)
            response.setHeader("Last-Modified", meta.getLastModifiedDateTime());
        if (meta.getETag() != null)
            response.setHeader("ETag", meta.getETag());
    }

    // ── PUT /{bucket}/{key} – PutObject ───────────────────────────────────────

    @PutMapping("/{bucket}/**")
    public void putObject(@PathVariable String bucket,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        String key = extractKey(request, bucket);
        log.debug("PutObject {}/{}", bucket, key);

        String contentType = request.getContentType();
        byte[] data = request.getInputStream().readAllBytes();

        DriveItem result;
        if (data.length <= SIMPLE_UPLOAD_THRESHOLD) {
            result = driveService.uploadSmall(bucket, key, data, contentType);
        } else {
            // Use a resumable session for large objects
            String uploadUrl = driveService.createUploadSession(bucket, key, contentType);
            driveService.uploadPart(uploadUrl, data, 0, data.length);
            result = driveService.getItemMetadata(bucket, key);
        }

        response.setStatus(200);
        if (result != null && result.getETag() != null)
            response.setHeader("ETag", result.getETag());
    }

    // ── DELETE /{bucket}/{key} – DeleteObject ─────────────────────────────────

    @DeleteMapping("/{bucket}/**")
    public void deleteObject(@PathVariable String bucket,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        String key = extractKey(request, bucket);
        log.debug("DeleteObject {}/{}", bucket, key);

        driveService.deleteItem(bucket, key);
        response.setStatus(204);
    }

    // ── POST /{bucket}/{key}?uploads – CreateMultipartUpload ──────────────────

    @PostMapping(value = "/{bucket}/**", params = "uploads",
                 produces = MediaType.APPLICATION_XML_VALUE)
    public void createMultipartUpload(@PathVariable String bucket,
                                      HttpServletRequest request,
                                      HttpServletResponse response) throws IOException {
        String key = extractKey(request, bucket);
        String contentType = request.getContentType();
        log.info("CreateMultipartUpload {}/{}", bucket, key);

        MultipartUpload upload = multipartStore.create(bucket, key, contentType);

        S3Xml.InitiateMultipartUploadResult xml = S3Xml.InitiateMultipartUploadResult.builder()
            .bucket(bucket)
            .key(key)
            .uploadId(upload.getUploadId())
            .build();

        response.setStatus(200);
        response.setContentType(MediaType.APPLICATION_XML_VALUE);
        XmlUtil.writeXml(response.getOutputStream(), xml);
    }

    // ── PUT /{bucket}/{key}?partNumber=N&uploadId=X – UploadPart ─────────────

    @PutMapping(value = "/{bucket}/**", params = {"partNumber", "uploadId"})
    public void uploadPart(@PathVariable String bucket,
                           @RequestParam int partNumber,
                           @RequestParam String uploadId,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        String key = extractKey(request, bucket);
        log.debug("UploadPart {}/{} part={} uploadId={}", bucket, key, partNumber, uploadId);

        byte[] data = request.getInputStream().readAllBytes();
        String etag = multipartStore.storePart(uploadId, partNumber, data);

        response.setStatus(200);
        response.setHeader("ETag", "\"" + etag + "\"");
    }

    // ── POST /{bucket}/{key}?uploadId=X – CompleteMultipartUpload ────────────

    @PostMapping(value = "/{bucket}/**", params = "uploadId",
                 produces = MediaType.APPLICATION_XML_VALUE)
    public void completeMultipartUpload(@PathVariable String bucket,
                                        @RequestParam String uploadId,
                                        HttpServletRequest request,
                                        HttpServletResponse response) throws IOException {
        String key = extractKey(request, bucket);
        log.info("CompleteMultipartUpload {}/{} uploadId={}", bucket, key, uploadId);

        // Parse part list from request body
        S3Xml.CompleteMultipartUploadRequest completeReq =
            XmlUtil.readXml(request.getInputStream(), S3Xml.CompleteMultipartUploadRequest.class);

        List<Integer> partNumbers = completeReq.getParts().stream()
            .map(S3Xml.PartSpec::getPartNumber)
            .sorted()
            .collect(Collectors.toList());

        List<Part> parts = multipartStore.getOrderedParts(uploadId, partNumbers);

        // Calculate total size
        long totalSize = parts.stream().mapToLong(Part::getSize).sum();

        // Create resumable upload session on OneDrive
        MultipartUpload upload = multipartStore.get(uploadId);
        String contentType = upload.getContentType();
        String uploadUrl = driveService.createUploadSession(bucket, key, contentType);

        // Upload each part sequentially
        long offset = 0;
        for (Part part : parts) {
            byte[] data = Files.readAllBytes(part.getTempFile().toPath());
            driveService.uploadPart(uploadUrl, data, offset, totalSize);
            offset += data.length;
        }

        multipartStore.complete(uploadId);

        DriveItem meta = driveService.getItemMetadata(bucket, key);
        String etag = meta != null && meta.getETag() != null ? meta.getETag() : "\"completed\"";

        S3Xml.CompleteMultipartUploadResult xml = S3Xml.CompleteMultipartUploadResult.builder()
            .location("http://localhost:8080/" + bucket + "/" + key)
            .bucket(bucket)
            .key(key)
            .eTag(etag)
            .build();

        response.setStatus(200);
        response.setContentType(MediaType.APPLICATION_XML_VALUE);
        XmlUtil.writeXml(response.getOutputStream(), xml);
    }

    // ── DELETE /{bucket}/{key}?uploadId=X – AbortMultipartUpload ─────────────

    @DeleteMapping(value = "/{bucket}/**", params = "uploadId")
    public void abortMultipartUpload(@PathVariable String bucket,
                                     @RequestParam String uploadId,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        String key = extractKey(request, bucket);
        log.info("AbortMultipartUpload {}/{} uploadId={}", bucket, key, uploadId);
        multipartStore.abort(uploadId);
        response.setStatus(204);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Extracts the object key from the URL path after /{bucket}/.
     * Uses /** mapping so slashes in keys are preserved.
     */
    private String extractKey(HttpServletRequest request, String bucket) {
        String uri = request.getRequestURI();
        String prefix = "/" + bucket + "/";
        if (uri.startsWith(prefix)) {
            return uri.substring(prefix.length());
        }
        return uri.substring(prefix.length() - 1); // handle trailing slash edge case
    }

    private void sendError(HttpServletResponse response, int status,
                           String code, String message, String resource) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_XML_VALUE);
        S3Xml.S3Error error = S3Xml.S3Error.builder()
            .code(code).message(message).resource(resource)
            .requestId(UUID.randomUUID().toString())
            .build();
        XmlUtil.writeXml(response.getOutputStream(), error);
    }
}
