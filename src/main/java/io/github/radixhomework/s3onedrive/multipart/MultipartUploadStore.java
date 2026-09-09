package io.github.radixhomework.s3onedrive.multipart;

import io.github.radixhomework.s3onedrive.config.MultipartProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages in-progress S3 multipart uploads.
 *
 * Each upload is identified by an uploadId.
 * Parts are stored as temporary files on disk to avoid OOM with large objects.
 */
@Slf4j
@Component
public class MultipartUploadStore {

    private final Map<String, MultipartUpload> uploads = new ConcurrentHashMap<>();
    private final Path tempDir;

    public MultipartUploadStore(MultipartProperties properties) throws IOException {
        this.tempDir = Paths.get(properties.getTempDir());
        Files.createDirectories(tempDir);
        log.info("Multipart temp dir: {}", tempDir);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public MultipartUpload create(String bucket, String key, String contentType) {
        String uploadId = UUID.randomUUID().toString();
        MultipartUpload upload = new MultipartUpload(uploadId, bucket, key, contentType, Instant.now());
        uploads.put(uploadId, upload);
        log.debug("Created multipart upload {} for {}/{}", uploadId, bucket, key);
        return upload;
    }

    public MultipartUpload get(String uploadId) {
        return uploads.get(uploadId);
    }

    public void abort(String uploadId) {
        MultipartUpload upload = uploads.remove(uploadId);
        if (upload != null) {
            upload.getParts().forEach((num, part) -> {
                try { Files.deleteIfExists(part.getTempFile().toPath()); }
                catch (IOException e) { log.warn("Could not delete temp file", e); }
            });
            log.debug("Aborted multipart upload {}", uploadId);
        }
    }

    public void complete(String uploadId) {
        uploads.remove(uploadId);
    }

    // ── Part storage ──────────────────────────────────────────────────────────

    /**
     * Persists a part to disk and records it in the upload.
     *
     * @param uploadId  Upload ID
     * @param partNumber  S3 part number (1-based)
     * @param data  Part bytes
     * @return MD5 ETag of the part (hex)
     */
    public String storePart(String uploadId, int partNumber, byte[] data) throws IOException {
        MultipartUpload upload = get(uploadId);
        if (upload == null) throw new IllegalArgumentException("Unknown uploadId: " + uploadId);

        File partFile = tempDir.resolve(uploadId + "_part" + partNumber).toFile();
        try (FileOutputStream fos = new FileOutputStream(partFile)) {
            fos.write(data);
        }

        String etag = md5Hex(data);
        upload.getParts().put(partNumber, new Part(partNumber, partFile, data.length, etag));
        log.debug("Stored part {} for upload {} ({} bytes)", partNumber, uploadId, data.length);
        return etag;
    }

    /**
     * Returns parts sorted by part number and validates the requested numbers match.
     */
    public List<Part> getOrderedParts(String uploadId, List<Integer> requestedNumbers) {
        MultipartUpload upload = get(uploadId);
        if (upload == null) throw new IllegalArgumentException("Unknown uploadId: " + uploadId);

        return requestedNumbers.stream()
            .sorted()
            .map(n -> {
                Part p = upload.getParts().get(n);
                if (p == null) throw new IllegalArgumentException("Missing part: " + n);
                return p;
            })
            .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String md5Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    @Data
    public static class MultipartUpload {
        private final String uploadId;
        private final String bucket;
        private final String key;
        private final String contentType;
        private final Instant createdAt;
        private final Map<Integer, Part> parts = new TreeMap<>();
    }

    @Data
    public static class Part {
        private final int partNumber;
        private final File tempFile;
        private final long size;
        private final String etag;
    }
}
