package io.github.radixhomework.s3onedrive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "s3")
public class S3Properties {

    /** The AWS access key that S3 clients must present */
    private String accessKey = "minioadmin";

    /** The AWS secret key used to verify SigV4 signatures */
    private String secretKey = "minioadmin";

    /** Virtual region returned in responses */
    private String region = "us-east-1";
}
