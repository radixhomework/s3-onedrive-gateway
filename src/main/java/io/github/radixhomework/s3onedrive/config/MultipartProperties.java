package io.github.radixhomework.s3onedrive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "multipart")
public class MultipartProperties {

    /** Directory where in-progress part files are stored temporarily */
    private String tempDir = System.getProperty("java.io.tmpdir") + "/s3-gateway-multipart";

    /** Maximum accepted size for a single part (bytes) */
    private long maxPartSize = 5L * 1024 * 1024 * 1024; // 5 GiB
}
