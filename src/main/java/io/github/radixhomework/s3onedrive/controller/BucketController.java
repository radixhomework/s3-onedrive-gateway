package io.github.radixhomework.s3onedrive.controller;

import io.github.radixhomework.s3onedrive.model.S3Xml;
import io.github.radixhomework.s3onedrive.service.OneDriveService;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItem;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItemList;
import io.github.radixhomework.s3onedrive.util.XmlUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles S3 bucket-level requests:
 *   GET  /           → ListBuckets
 *   PUT  /{bucket}   → CreateBucket
 *   DELETE /{bucket} → DeleteBucket
 *   HEAD /{bucket}   → HeadBucket
 */
@Slf4j
@RestController
public class BucketController {

    private final OneDriveService driveService;

    public BucketController(OneDriveService driveService) {
        this.driveService = driveService;
    }

    // ── GET / – ListBuckets ───────────────────────────────────────────────────

    @GetMapping(value = "/", produces = MediaType.APPLICATION_XML_VALUE)
    public void listBuckets(HttpServletResponse response) throws IOException {
        log.debug("ListBuckets");

        DriveItemList result = driveService.listRootChildren();

        List<S3Xml.Bucket> buckets = result != null && result.getItems() != null
            ? result.getItems().stream()
                .filter(item -> item.getFolder() != null)  // folders only
                .map(item -> S3Xml.Bucket.builder()
                    .name(item.getName())
                    .creationDate(item.getCreatedDateTime())
                    .build())
                .collect(Collectors.toList())
            : Collections.emptyList();

        S3Xml.ListBucketsResult xml = S3Xml.ListBucketsResult.builder()
            .owner(S3Xml.Owner.builder()
                .id("onedrive-gateway")
                .displayName("OneDrive Gateway")
                .build())
            .buckets(S3Xml.BucketList.builder()
                .bucket(buckets)
                .build())
            .build();

        response.setStatus(200);
        response.setContentType(MediaType.APPLICATION_XML_VALUE);
        XmlUtil.writeXml(response.getOutputStream(), xml);
    }

    // ── PUT /{bucket} – CreateBucket ──────────────────────────────────────────

    @PutMapping("/{bucket}")
    public void createBucket(@PathVariable String bucket,
                             HttpServletResponse response) throws IOException {
        log.info("CreateBucket: {}", bucket);

        driveService.createFolder(bucket);

        response.setStatus(200);
        response.setHeader("Location", "/" + bucket);
    }

    // ── DELETE /{bucket} – DeleteBucket ──────────────────────────────────────

    @DeleteMapping("/{bucket}")
    public void deleteBucket(@PathVariable String bucket,
                             HttpServletResponse response) throws IOException {
        log.info("DeleteBucket: {}", bucket);

        boolean deleted = driveService.deleteItem(bucket, null);
        response.setStatus(deleted ? 204 : 404);
    }

    // ── HEAD /{bucket} – HeadBucket ───────────────────────────────────────────

    @RequestMapping(method = RequestMethod.HEAD, value = "/{bucket}")
    public void headBucket(@PathVariable String bucket,
                           HttpServletResponse response) {
        log.debug("HeadBucket: {}", bucket);

        DriveItem item = driveService.getItemMetadata(bucket, null);
        response.setStatus(item != null ? 200 : 404);
    }
}
