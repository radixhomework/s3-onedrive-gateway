package io.github.radixhomework.s3onedrive.controller;

import io.github.radixhomework.s3onedrive.exception.S3Exception;
import io.github.radixhomework.s3onedrive.model.S3Xml;
import io.github.radixhomework.s3onedrive.multipart.MultipartUploadStore;
import io.github.radixhomework.s3onedrive.multipart.MultipartUploadStore.MultipartUpload;
import io.github.radixhomework.s3onedrive.multipart.MultipartUploadStore.Part;
import io.github.radixhomework.s3onedrive.service.ListObjectsService;
import io.github.radixhomework.s3onedrive.service.OneDriveService;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItem;
import io.github.radixhomework.s3onedrive.util.XmlUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles S3 object-level requests:
 *
 *   GET    /{bucket}           → ListObjects (V1 & V2, recursive)
 *   GET    /{bucket}/{key}     → GetObject (Range + conditional)
 *   PUT    /{bucket}/{key}     → PutObject (or CopyObject with x-amz-copy-source)
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
    /** Resumable-session chunk size; must be a multiple of 320 KiB. */
    private static final int STREAM_CHUNK_SIZE = 8 * 1024 * 1024;

    private final OneDriveService driveService;
    private final MultipartUploadStore multipartStore;
    private final ListObjectsService listObjectsService;

    public ObjectController(OneDriveService driveService,
                            MultipartUploadStore multipartStore,
                            ListObjectsService listObjectsService) {
        this.driveService       = driveService;
        this.multipartStore     = multipartStore;
        this.listObjectsService = listObjectsService;
    }

    // ── GET /{bucket} – ListObjects (V1 & V2) ─────────────────────────────────

    @GetMapping(value = "/{bucket}", produces = MediaType.APPLICATION_XML_VALUE)
    public void listObjects(@PathVariable String bucket,
                            @RequestParam(required = false) String listType,
                            @RequestParam(defaultValue = "") String prefix,
                            @RequestParam(defaultValue = "") String delimiter,
                            @RequestParam(value = "max-keys", defaultValue = "1000") int maxKeys,
                            @RequestParam(required = false) String marker,
                            @RequestParam(value = "continuation-token", required = false) String continuationToken,
                            @RequestParam(value = "start-after", required = false) String startAfter,
                            @RequestParam(value = "encoding-type", required = false) String encodingType,
                            HttpServletResponse response) throws IOException {

        boolean v2 = "2".equals(listType);
        log.debug("ListObjects bucket={} prefix={} delimiter={} maxKeys={} v2={}",
            bucket, prefix, delimiter, maxKeys, v2);

        // V1 markers resume by "skip everything up to and including the marker key"
        String effectiveStartAfter = startAfter;
        if (!v2 && effectiveStartAfter == null && marker != null && !marker.isBlank()) {
            effectiveStartAfter = marker;
        }

        ListObjectsService.Result result = listObjectsService.list(bucket,
            new ListObjectsService.Request(prefix, delimiter, maxKeys, continuationToken, effectiveStartAfter));

        boolean urlEncode = "url".equals(encodingType);
        S3Xml.ListObjectsResult.ListObjectsResultBuilder builder = S3Xml.ListObjectsResult.builder()
            .name(bucket)
            .prefix(encode(prefix, urlEncode))
            .delimiter(delimiter.isBlank() ? null : delimiter)
            .maxKeys(maxKeys)
            .keyCount(result.objects().size() + result.commonPrefixes().size())
            .encodingType(urlEncode ? "url" : null)
            .isTruncated(result.truncated());

        if (v2) {
            builder
                .continuationToken(blankToNull(continuationToken))
                .startAfter(blankToNull(encode(startAfter, urlEncode)))
                .nextContinuationToken(blankToNull(result.nextContinuationToken()));
        } else {
            builder
                .marker(blankToNull(encode(marker, urlEncode)))
                .nextMarker(result.truncated()
                    ? encode(result.objects().isEmpty()
                        ? result.commonPrefixes().get(result.commonPrefixes().size() - 1)
                        : result.objects().get(result.objects().size() - 1).key(), urlEncode)
                    : null);
        }

        List<S3Xml.S3Object> contents = result.objects().stream()
            .map(o -> S3Xml.S3Object.builder()
                .key(encode(o.key(), urlEncode))
                .lastModified(o.lastModified())
                .eTag(o.etag())
                .size(o.size())
                .storageClass("STANDARD")
                .build())
            .collect(Collectors.toList());
        builder.contents(contents.isEmpty() ? null : contents);

        List<S3Xml.CommonPrefix> commonPrefixes = result.commonPrefixes().stream()
            .map(p -> S3Xml.CommonPrefix.builder().prefix(encode(p, urlEncode)).build())
            .collect(Collectors.toList());
        builder.commonPrefixes(commonPrefixes.isEmpty() ? null : commonPrefixes);

        response.setStatus(200);
        response.setContentType(MediaType.APPLICATION_XML_VALUE);
        XmlUtil.writeXml(response.getOutputStream(), builder.build());
    }

    // ── GET /{bucket}/{key} – GetObject ───────────────────────────────────────

    @GetMapping("/{bucket}/**")
    public void getObject(@PathVariable String bucket,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        String key = extractKey(request, bucket);
        log.debug("GetObject {}/{}", bucket, key);

        DriveItem meta = driveService.getItemMetadata(bucket, key);
        if (meta == null) {
            sendError(response, 404, "NoSuchKey", "The specified key does not exist.", "/" + bucket + "/" + key);
            return;
        }

        String etag = normalizeEtag(meta.getETag() != null ? meta.getETag() : meta.getId());

        // Conditional requests
        if (etagMatches(request.getHeader("If-None-Match"), etag)) {
            response.setStatus(304);
            response.setHeader("ETag", etag);
            return;
        }
        if (request.getHeader("If-Match") != null && !etagMatches(request.getHeader("If-Match"), etag)) {
            sendError(response, 412, "PreconditionFailed", "At least one of the pre-conditions did not match",
                "/" + bucket + "/" + key);
            return;
        }

        String rangeHeader = request.getHeader("Range");
        ByteRange range = null;
        if (rangeHeader != null && meta.getSize() != null) {
            range = parseRange(rangeHeader, meta.getSize()); // throws 416 when unsatisfiable
        }

        ResponseEntity<Flux<DataBuffer>> entity =
            driveService.downloadItem(bucket, key, range != null ? rangeHeader : null);

        boolean partial = range != null && entity.getStatusCode().value() == 206;
        response.setStatus(partial ? 206 : 200);
        if (partial) {
            response.setHeader("Content-Range",
                "bytes " + range.start() + "-" + range.end() + "/" + meta.getSize());
            response.setContentLengthLong(range.end() - range.start() + 1);
        } else if (meta.getSize() != null) {
            response.setContentLengthLong(meta.getSize());
        }
        if (meta.getLastModifiedDateTime() != null) {
            response.setHeader("Last-Modified", meta.getLastModifiedDateTime());
        }
        response.setHeader("ETag", etag);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        stream(entity.getBody(), response);
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

        String etag = normalizeEtag(meta.getETag() != null ? meta.getETag() : meta.getId());
        if (etagMatches(request.getHeader("If-None-Match"), etag)) {
            response.setStatus(304);
            response.setHeader("ETag", etag);
            return;
        }
        if (request.getHeader("If-Match") != null && !etagMatches(request.getHeader("If-Match"), etag)) {
            response.setStatus(412);
            return;
        }

        response.setStatus(200);
        if (meta.getSize() != null) response.setContentLengthLong(meta.getSize());
        if (meta.getLastModifiedDateTime() != null) {
            response.setHeader("Last-Modified", meta.getLastModifiedDateTime());
        }
        response.setHeader("ETag", etag);
    }

    // ── PUT /{bucket}/{key} – PutObject / CopyObject ──────────────────────────

    @PutMapping("/{bucket}/**")
    public void putObject(@PathVariable String bucket,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        String key = extractKey(request, bucket);

        String copySource = request.getHeader("x-amz-copy-source");
        if (copySource != null && !copySource.isBlank()) {
            copyObject(copySource, bucket, key, request, response);
            return;
        }

        log.debug("PutObject {}/{}", bucket, key);
        String contentType = request.getContentType();

        // Spool the (already SigV4-decoded) body to disk: never buffers a
        // whole large upload in memory.
        Path spool = Files.createTempFile("s3gw-put-", ".tmp");
        try {
            long size;
            try (InputStream in = request.getInputStream();
                 OutputStream out = Files.newOutputStream(spool)) {
                size = in.transferTo(out);
            }

            DriveItem result;
            if (size <= SIMPLE_UPLOAD_THRESHOLD) {
                result = driveService.uploadSmall(bucket, key, Files.readAllBytes(spool), contentType);
            } else {
                result = uploadStreamed(bucket, key, spool, size, contentType);
            }

            response.setStatus(200);
            if (result != null && result.getETag() != null) {
                response.setHeader("ETag", normalizeEtag(result.getETag()));
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /** Server-side copy implemented as download → upload through the gateway. */
    private void copyObject(String copySource, String destBucket, String destKey,
                            HttpServletRequest request, HttpServletResponse response) throws IOException {
        String[] src = parseCopySource(copySource);
        log.info("CopyObject {}/{} -> {}/{}", src[0], src[1], destBucket, destKey);

        DriveItem meta = driveService.getItemMetadata(src[0], src[1]);
        if (meta == null) {
            sendError(response, 404, "NoSuchKey", "The specified copy source does not exist.", copySource);
            return;
        }

        String contentType = request.getContentType();
        Path spool = Files.createTempFile("s3gw-copy-", ".tmp");
        try {
            long size;
            try (OutputStream out = Files.newOutputStream(spool)) {
                size = pumpTo(driveService.downloadItem(src[0], src[1], null).getBody(), out);
            }

            DriveItem result;
            if (size <= SIMPLE_UPLOAD_THRESHOLD) {
                result = driveService.uploadSmall(destBucket, destKey, Files.readAllBytes(spool), contentType);
            } else {
                result = uploadStreamed(destBucket, destKey, spool, size, contentType);
            }

            String etag = result != null && result.getETag() != null
                ? normalizeEtag(result.getETag())
                : normalizeEtag(meta.getETag());
            String lastModified = result != null && result.getLastModifiedDateTime() != null
                ? result.getLastModifiedDateTime()
                : meta.getLastModifiedDateTime();

            response.setStatus(200);
            response.setContentType(MediaType.APPLICATION_XML_VALUE);
            XmlUtil.writeXml(response.getOutputStream(), S3Xml.CopyObjectResult.builder()
                .eTag(etag)
                .lastModified(lastModified)
                .build());
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /**
     * Uploads a spooled file through a Graph resumable session in fixed-size
     * chunks (a multiple of the 320 KiB alignment Graph requires).
     */
    private DriveItem uploadStreamed(String bucket, String key, Path file, long size, String contentType) {
        String uploadUrl = driveService.createUploadSession(bucket, key, contentType);
        long offset = 0;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[STREAM_CHUNK_SIZE];
            int n;
            while ((n = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                driveService.uploadPart(uploadUrl, Arrays.copyOf(buffer, n), offset, size);
                offset += n;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return driveService.getItemMetadata(bucket, key);
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

        // Reject unknown uploads before buffering the body
        if (multipartStore.get(uploadId) == null) {
            throw new S3Exception(404, "NoSuchUpload", "Unknown uploadId: " + uploadId);
        }

        // Spool to disk first so the part-size limit doesn't depend on heap
        Path spool = Files.createTempFile("s3gw-part-", ".tmp");
        try {
            long size;
            try (InputStream in = request.getInputStream();
                 OutputStream out = Files.newOutputStream(spool)) {
                size = in.transferTo(out);
            }
            if (size > Integer.MAX_VALUE - 1) {
                throw new S3Exception(400, "EntityTooLarge", "Part exceeds supported size");
            }
            byte[] data = Files.readAllBytes(spool);
            String etag = multipartStore.storePart(uploadId, partNumber, data);
            response.setStatus(200);
            response.setHeader("ETag", "\"" + etag + "\"");
        } finally {
            Files.deleteIfExists(spool);
        }
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

        S3Xml.CompleteMultipartUploadRequest completeReq =
            XmlUtil.readXml(request.getInputStream(), S3Xml.CompleteMultipartUploadRequest.class);

        if (completeReq == null || completeReq.getParts() == null || completeReq.getParts().isEmpty()) {
            throw new S3Exception(400, "MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema");
        }

        MultipartUpload upload = multipartStore.get(uploadId);
        if (upload == null) {
            throw new S3Exception(404, "NoSuchUpload", "Unknown uploadId: " + uploadId);
        }

        // Validate the client-provided part ETags before touching Graph
        for (S3Xml.PartSpec spec : completeReq.getParts()) {
            Part stored = multipartStore.getPart(uploadId, spec.getPartNumber());
            if (spec.getETag() != null && !spec.getETag().isBlank()
                && !etagEqualsIgnoreQuotes(spec.getETag(), stored.getEtag())) {
                throw new S3Exception(400, "InvalidPart",
                    "One or more of the specified parts could not be found or have invalid ETag");
            }
        }

        List<Integer> partNumbers = completeReq.getParts().stream()
            .map(S3Xml.PartSpec::getPartNumber)
            .sorted()
            .collect(Collectors.toList());
        List<Part> parts = multipartStore.getOrderedParts(uploadId, partNumbers);
        long totalSize = parts.stream().mapToLong(Part::getSize).sum();

        // Stream the parts to a Graph resumable session in fixed-size chunks
        String uploadUrl = driveService.createUploadSession(bucket, key, upload.getContentType());
        long offset = 0;
        try {
            byte[] buffer = new byte[STREAM_CHUNK_SIZE];
            for (Part part : parts) {
                try (InputStream in = Files.newInputStream(part.getTempFile().toPath())) {
                    int n;
                    while ((n = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                        driveService.uploadPart(uploadUrl, Arrays.copyOf(buffer, n), offset, totalSize);
                        offset += n;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        multipartStore.complete(uploadId);

        DriveItem meta = driveService.getItemMetadata(bucket, key);
        String etag = meta != null && meta.getETag() != null
            ? normalizeEtag(meta.getETag()) : "\"completed\"";

        String baseUrl = request.getRequestURL().toString()
            .replace(request.getRequestURI(), "");

        S3Xml.CompleteMultipartUploadResult xml = S3Xml.CompleteMultipartUploadResult.builder()
            .location(baseUrl + "/" + bucket + "/" + key)
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
     * Extracts the object key from the URL path after /{bucket}/ and URL-decodes it.
     * Uses /** mapping so slashes in keys are preserved.
     */
    private String extractKey(HttpServletRequest request, String bucket) {
        String uri = request.getRequestURI();
        String prefix = "/" + bucket + "/";
        String raw;
        if (uri.startsWith(prefix)) {
            raw = uri.substring(prefix.length());
        } else {
            raw = uri.substring(prefix.length() - 1); // trailing-slash edge case
        }
        // Percent-decode; "+" must stay a literal plus (S3 keys are form-encoding free)
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** Parses "bucket/key" (URL-encoded, optional leading slash, optional ?versionId). */
    private static String[] parseCopySource(String copySource) {
        String source = copySource;
        int query = source.indexOf('?');
        if (query >= 0) source = source.substring(0, query);
        if (source.startsWith("/")) source = source.substring(1);
        int slash = source.indexOf('/');
        if (slash <= 0 || slash == source.length() - 1) {
            throw new S3Exception(400, "InvalidArgument", "Invalid x-amz-copy-source: " + copySource);
        }
        return new String[] {
            decodePathSegment(source.substring(0, slash)),
            decodePathSegment(source.substring(slash + 1))
        };
    }

    private static String decodePathSegment(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** Streams a DataBuffer Flux to the servlet response. */
    private static void stream(Flux<DataBuffer> content, HttpServletResponse response) throws IOException {
        try (OutputStream out = response.getOutputStream()) {
            pumpTo(content, out);
            out.flush();
        }
    }

    private static long pumpTo(Flux<DataBuffer> content, OutputStream out) throws IOException {
        long total = 0;
        for (DataBuffer buf : content.toIterable()) {
            try {
                byte[] bytes = new byte[buf.readableByteCount()];
                buf.read(bytes);
                out.write(bytes);
                total += bytes.length;
            } finally {
                DataBufferUtils.release(buf);
            }
        }
        return total;
    }

    private record ByteRange(long start, long end) {}

    /**
     * Parses a single-range "bytes=..." header. Returns null when the header is
     * absent or unsupported (multi-range) → full content. Throws 416 when unsatisfiable.
     */
    private static ByteRange parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=")) return null;
        String spec = header.substring("bytes=".length()).trim();
        if (spec.contains(",")) return null; // multi-range unsupported → full content

        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();

        try {
            if (startStr.isEmpty()) {
                // suffix range: last N bytes
                long n = Long.parseLong(endStr);
                if (n <= 0 || size <= 0) throw unsatisfiable(size);
                return new ByteRange(Math.max(0, size - n), size - 1);
            }
            long start = Long.parseLong(startStr);
            long end = endStr.isEmpty() ? size - 1 : Math.min(Long.parseLong(endStr), size - 1);
            if (start > end || start >= size) throw unsatisfiable(size);
            return new ByteRange(start, end);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static S3Exception unsatisfiable(long size) {
        return new S3Exception(416, "InvalidRange",
            "The requested range is not satisfiable (object size: " + size + ")");
    }

    private static String normalizeEtag(String etag) {
        if (etag == null) return null;
        return etag.startsWith("\"") ? etag : "\"" + etag + "\"";
    }

    private static boolean etagMatches(String headerValue, String etag) {
        if (headerValue == null || etag == null) return false;
        for (String candidate : headerValue.split(",")) {
            candidate = candidate.trim();
            if (candidate.equals("*")) return true;
            if (candidate.startsWith("W/")) candidate = candidate.substring(2);
            if (etagEqualsIgnoreQuotes(candidate, etag)) return true;
        }
        return false;
    }

    private static boolean etagEqualsIgnoreQuotes(String a, String b) {
        return a.replace("\"", "").equalsIgnoreCase(b.replace("\"", ""));
    }

    private static String encode(String value, boolean urlEncode) {
        if (value == null || !urlEncode) return value;
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b));
            }
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
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
