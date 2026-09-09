package io.github.radixhomework.s3onedrive.exception;

import lombok.Getter;

/**
 * An S3 protocol error carrying the HTTP status and S3 error code
 * to serialize in the XML error response.
 */
@Getter
public class S3Exception extends RuntimeException {

    private final int status;
    private final String code;

    public S3Exception(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
