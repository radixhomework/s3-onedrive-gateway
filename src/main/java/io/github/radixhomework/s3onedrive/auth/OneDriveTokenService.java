package io.github.radixhomework.s3onedrive.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.radixhomework.s3onedrive.config.OneDriveProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Acquires and caches an OAuth 2.0 access token for Microsoft Graph
 * using the Client Credentials flow (app-only, no user required).
 */
@Slf4j
@Service
public class OneDriveTokenService {

    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";
    private static final String TOKEN_CACHE_NAME = "graphToken";

    private final OneDriveProperties properties;
    private final WebClient tokenClient;

    public OneDriveTokenService(OneDriveProperties properties) {
        this.properties = properties;
        this.tokenClient = WebClient.builder()
            .baseUrl("https://login.microsoftonline.com")
            .build();
    }

    /**
     * Returns a valid Bearer token, fetching a fresh one if not cached.
     * Caffeine cache is configured with expireAfterWrite=50m (tokens expire after 60m).
     */
    @Cacheable(TOKEN_CACHE_NAME)
    public String getBearerToken() {
        log.debug("Fetching new Microsoft Graph access token");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type",    "client_credentials");
        form.add("client_id",     properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("scope",         GRAPH_SCOPE);

        TokenResponse resp = tokenClient.post()
            .uri("/{tenantId}/oauth2/v2.0/token", properties.getTenantId())
            .body(BodyInserters.fromFormData(form))
            .retrieve()
            .bodyToMono(TokenResponse.class)
            .block();

        if (resp == null || resp.accessToken == null) {
            throw new IllegalStateException("Failed to obtain Microsoft Graph access token");
        }

        log.debug("Token acquired, expires_in={}s", resp.expiresIn);
        return resp.accessToken;
    }

    /** Force token refresh every 50 minutes as a safety net. */
    @Scheduled(fixedDelay = 50 * 60 * 1000)
    @CacheEvict(value = TOKEN_CACHE_NAME, allEntries = true)
    public void evictTokenCache() {
        log.debug("Graph token cache evicted");
    }

    // ── DTO ──────────────────────────────────────────────────────────────────

    @Data
    static class TokenResponse {
        @JsonProperty("access_token")
        String accessToken;

        @JsonProperty("expires_in")
        int expiresIn;

        @JsonProperty("token_type")
        String tokenType;
    }
}
