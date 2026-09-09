package io.github.radixhomework.s3onedrive.multipart;

import io.github.radixhomework.s3onedrive.config.MultipartProperties;
import io.github.radixhomework.s3onedrive.exception.S3Exception;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages in-progress S3 multipart uploads.
 *
 * Each upload is identified by an uploadId.
 * Parts are stored as temporary files on disk to avoid OOM with large objects.
 * Upload state is in-memory: leftover part files from a previous run are
 * removed at startup, and stale uploads are reaped periodically.
 */
@Slf4j
@Component
public class MultipartUploadStore {

    /** Uploads not completed within this window are reaped. */
    private static final Duration UPLOAD_TTL = Duration.ofDays(7);

    private final Map<String, MultipartUpload> uploads = new ConcurrentHashMap<>();
    private final Path tempDir;

    public MultipartUploadStore(MultipartProperties properties) throws IOException {
        this.tempDir = Paths.get(properties.getTempDir());
        Files.createDirectories(tempDir);
        cleanupOrphans();
        log.info("Multipart temp dir: {}", tempDir);
    }

    /** Upload state is memory-only, so every file left over from a previous run is an orphan. */
    private void cleanupOrphans() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            List<Path> orphans = files.toList();
            for (Path orphan : orphans) {
                try { Files.deleteIfExists(orphan); }
                catch (IOException e) { log.warn("Could not delete orphaned part file {}", orphan, e); }
            }
            if (!orphans.isEmpty()) {
                log.warn("Deleted {} orphaned multipart part file(s) from previous run", orphans.size());
            }
        }
    }

    /** Reaps uploads abandoned past the TTL (client crashed without abort). */
    @Scheduled(fixedDelay = 3600_000)
    public void expireStaleUploads() {
        Instant cutoff = Instant.now().minus(UPLOAD_TTL);
        uploads.values().stream()
            .filter(u -> u.getCreatedAt().isBefore(cutoff))
            .forEach(u -> {
                log.warn("Expiring stale multipart upload {} ({}/{})",
                    u.getUploadId(), u.getBucket(), u.getKey());
                abort(u.getUploadId());
            });
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
            upload.getParts().values().forEach(part -> {
                try { Files.deleteIfExists(part.getTempFile().toPath()); }
                catch (IOException e) { log.warn("Could not delete temp file", e); }
            });
            log.debug("Aborted multipart upload {}", uploadId);
        }
    }

    public void complete(String uploadId) {
        MultipartUpload upload = uploads.remove(uploadId);
        if (upload != null) {
            upload.getParts().values().forEach(part -> {
                try { Files.deleteIfExists(part.getTempFile().toPath()); }
                catch (IOException e) { log.warn("Could not delete temp file", e); }
            });
        }
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
        if (upload == null) throw new S3Exception(404, "NoSuchUpload", "Unknown uploadId: " + uploadId);

        File partFile = tempDir.resolve(uploadId + "_part" + partNumber).toFile();
        try (FileOutputStream fos = new FileOutputStream(partFile)) {
            fos.write(data);
        }

        String etag = md5Hex(data);
        upload.getParts().put(partNumber, new Part(partNumber, partFile, data.length, etag));
        log.debug("Stored part {} for upload {} ({} bytes)", partNumber, uploadId, data.length);
        return etag;
    }

    /** Returns a single stored part, or throws NoSuchUpload/InvalidPart. */
    public Part getPart(String uploadId, int partNumber) {
        MultipartUpload upload = get(uploadId);
        if (upload == null) throw new S3Exception(404, "NoSuchUpload", "Unknown uploadId: " + uploadId);
        Part part = upload.getParts().get(partNumber);
        if (part == null) {
            throw new S3Exception(400, "InvalidPart", "Part " + partNumber + " was not uploaded");
        }
        return part;
    }

    /**
     * Returns parts sorted by part number and validates the requested numbers match.
     */
    public List<Part> getOrderedParts(String uploadId, List<Integer> requestedNumbers) {
        MultipartUpload upload = get(uploadId);
        if (upload == null) throw new S3Exception(404, "NoSuchUpload", "Unknown uploadId: " + uploadId);

        return requestedNumbers.stream()
            .sorted()
            .map(n -> {
                Part p = upload.getParts().get(n);
                if (p == null) throw new S3Exception(400, "InvalidPart", "Missing part: " + n);
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
