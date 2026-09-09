package io.github.radixhomework.s3onedrive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "onedrive")
public class OneDriveProperties {

    /** Azure AD tenant ID */
    private String tenantId;

    /** Azure AD application (client) ID */
    private String clientId;

    /** Azure AD client secret */
    private String clientSecret;

    /** Optional specific drive ID (leave blank for default drive) */
    private String driveId;

    /** Root folder name inside the drive used as the S3 namespace */
    private String rootFolder = "s3";
}
