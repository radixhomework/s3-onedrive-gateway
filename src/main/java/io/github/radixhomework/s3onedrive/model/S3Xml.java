package io.github.radixhomework.s3onedrive.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * XML model POJOs used in S3 protocol responses.
 * Serialised with Jackson XML mapper.
 */
public final class S3Xml {

    private S3Xml() {}

    // ── ListAllMyBucketsResult ────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JacksonXmlRootElement(localName = "ListAllMyBucketsResult",
        namespace = "http://s3.amazonaws.com/doc/2006-03-01/")
    public static class ListBucketsResult {
        private Owner owner;

        @JacksonXmlProperty(localName = "Buckets")
        private BucketList buckets;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BucketList {
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "Bucket")
        private List<Bucket> bucket;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Bucket {
        @JacksonXmlProperty(localName = "Name")
        private String name;
        @JacksonXmlProperty(localName = "CreationDate")
        private String creationDate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Owner {
        @JacksonXmlProperty(localName = "ID")
        private String id;
        @JacksonXmlProperty(localName = "DisplayName")
        private String displayName;
    }

    // ── ListBucketResult ──────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JacksonXmlRootElement(localName = "ListBucketResult",
        namespace = "http://s3.amazonaws.com/doc/2006-03-01/")
    public static class ListObjectsResult {
        @JacksonXmlProperty(localName = "Name")
        private String name;
        @JacksonXmlProperty(localName = "Prefix")
        private String prefix;
        @JacksonXmlProperty(localName = "Delimiter")
        private String delimiter;
        @JacksonXmlProperty(localName = "MaxKeys")
        private int maxKeys;
        @JacksonXmlProperty(localName = "IsTruncated")
        private boolean isTruncated;

        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "Contents")
        private List<S3Object> contents;

        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "CommonPrefixes")
        private List<CommonPrefix> commonPrefixes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class S3Object {
        @JacksonXmlProperty(localName = "Key")
        private String key;
        @JacksonXmlProperty(localName = "LastModified")
        private String lastModified;
        @JacksonXmlProperty(localName = "ETag")
        private String eTag;
        @JacksonXmlProperty(localName = "Size")
        private long size;
        @JacksonXmlProperty(localName = "StorageClass")
        private String storageClass;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CommonPrefix {
        @JacksonXmlProperty(localName = "Prefix")
        private String prefix;
    }

    // ── InitiateMultipartUploadResult ─────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JacksonXmlRootElement(localName = "InitiateMultipartUploadResult",
        namespace = "http://s3.amazonaws.com/doc/2006-03-01/")
    public static class InitiateMultipartUploadResult {
        @JacksonXmlProperty(localName = "Bucket")
        private String bucket;
        @JacksonXmlProperty(localName = "Key")
        private String key;
        @JacksonXmlProperty(localName = "UploadId")
        private String uploadId;
    }

    // ── CompleteMultipartUploadResult ─────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JacksonXmlRootElement(localName = "CompleteMultipartUploadResult",
        namespace = "http://s3.amazonaws.com/doc/2006-03-01/")
    public static class CompleteMultipartUploadResult {
        @JacksonXmlProperty(localName = "Location")
        private String location;
        @JacksonXmlProperty(localName = "Bucket")
        private String bucket;
        @JacksonXmlProperty(localName = "Key")
        private String key;
        @JacksonXmlProperty(localName = "ETag")
        private String eTag;
    }

    // ── CompleteMultipartUpload (request) ─────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    @JacksonXmlRootElement(localName = "CompleteMultipartUpload")
    public static class CompleteMultipartUploadRequest {
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "Part")
        private List<PartSpec> parts;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PartSpec {
        @JacksonXmlProperty(localName = "PartNumber")
        private int partNumber;
        @JacksonXmlProperty(localName = "ETag")
        private String eTag;
    }

    // ── Error ─────────────────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JacksonXmlRootElement(localName = "Error")
    public static class S3Error {
        @JacksonXmlProperty(localName = "Code")
        private String code;
        @JacksonXmlProperty(localName = "Message")
        private String message;
        @JacksonXmlProperty(localName = "Resource")
        private String resource;
        @JacksonXmlProperty(localName = "RequestId")
        private String requestId;
    }
}
