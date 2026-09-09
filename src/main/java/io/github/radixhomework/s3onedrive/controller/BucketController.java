package io.github.radixhomework.s3onedrive.controller;

import io.github.radixhomework.s3onedrive.exception.S3Exception;
import io.github.radixhomework.s3onedrive.model.S3Xml;
import io.github.radixhomework.s3onedrive.service.OneDriveService;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItem;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItemList;
import io.github.radixhomework.s3onedrive.util.XmlUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles S3 bucket-level requests:
 *   GET  /             → ListBuckets
 *   PUT  /{bucket}     → CreateBucket
 *   DELETE /{bucket}   → DeleteBucket (refuses non-empty buckets)
 *   HEAD /{bucket}     → HeadBucket
 *   POST /{bucket}?delete → DeleteObjects (batch)
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
                             HttpServletResponse response) {
        log.info("DeleteBucket: {}", bucket);

        // Graph folder deletes are recursive: refuse like S3 instead of
        // destroying every object under the bucket.
        DriveItemList children;
        try {
            children = driveService.listBucketChildren(bucket, "");
        } catch (WebClientResponseException.NotFound e) {
            throw new S3Exception(404, "NoSuchBucket", "The specified bucket does not exist");
        }
        if (children != null && children.getItems() != null && !children.getItems().isEmpty()) {
            throw new S3Exception(409, "BucketNotEmpty",
                "The bucket you tried to delete is not empty");
        }

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

    // ── POST /{bucket}?delete – DeleteObjects (batch) ────────────────────────

    @PostMapping(value = "/{bucket}", params = "delete",
                 produces = MediaType.APPLICATION_XML_VALUE)
    public void deleteObjects(@PathVariable String bucket,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        log.info("DeleteObjects on {}", bucket);

        S3Xml.DeleteRequest deleteRequest =
            XmlUtil.readXml(request.getInputStream(), S3Xml.DeleteRequest.class);
        if (deleteRequest == null || deleteRequest.getObjects() == null
            || deleteRequest.getObjects().isEmpty()) {
            throw new S3Exception(400, "MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema");
        }

        boolean quiet = Boolean.TRUE.equals(deleteRequest.getQuiet());
        List<S3Xml.DeletedObject> deleted = new ArrayList<>();
        List<S3Xml.DeleteError> errors = new ArrayList<>();

        for (S3Xml.ObjectIdentifier object : deleteRequest.getObjects()) {
            String key = object.getKey();
            try {
                driveService.deleteItem(bucket, key);
                if (!quiet) deleted.add(S3Xml.DeletedObject.builder().key(key).build());
            } catch (Exception e) {
                log.warn("DeleteObjects failed for {}/{}: {}", bucket, key, e.getMessage());
                errors.add(S3Xml.DeleteError.builder()
                    .key(key)
                    .code("InternalError")
                    .message(e.getMessage())
                    .build());
            }
        }

        response.setStatus(200);
        response.setContentType(MediaType.APPLICATION_XML_VALUE);
        XmlUtil.writeXml(response.getOutputStream(), S3Xml.DeleteResult.builder()
            .deleted(deleted.isEmpty() ? null : deleted)
            .errors(errors.isEmpty() ? null : errors)
            .build());
    }
}
