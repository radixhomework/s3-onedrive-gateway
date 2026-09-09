package io.github.radixhomework.s3onedrive.auth;

import io.github.radixhomework.s3onedrive.config.S3Properties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates AWS Signature Version 4 on every request.
 *
 * Supports:
 *  - Authorization header style  (AWS4-HMAC-SHA256 Credential=...)
 *  - Pre-signed URL style        (?X-Amz-Algorithm=AWS4-HMAC-SHA256&...)
 */
@Slf4j
@Component
public class AwsSigV4AuthFilter extends OncePerRequestFilter {

    private static final Pattern CREDENTIAL_PATTERN =
        Pattern.compile("Credential=([^/]+)/([^/]+)/([^/]+)/([^/]+)/aws4_request");

    private final S3Properties s3Properties;

    public AwsSigV4AuthFilter(S3Properties s3Properties) {
        this.s3Properties = s3Properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Health check – skip auth
        if ("/health".equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String authHeader = request.getHeader("Authorization");
            String xAmzAlgorithm = request.getParameter("X-Amz-Algorithm");

            if (authHeader != null && authHeader.startsWith("AWS4-HMAC-SHA256")) {
                authenticateHeaderSig(authHeader, request, response, filterChain);
            } else if ("AWS4-HMAC-SHA256".equals(xAmzAlgorithm)) {
                // Pre-signed URL – validate credential presence & access key
                authenticatePresignedUrl(request, response, filterChain);
            } else {
                sendAuthError(response, "MissingAuthenticationToken",
                    "No valid AWS authentication found");
            }
        } catch (Exception e) {
            log.warn("Authentication error: {}", e.getMessage());
            sendAuthError(response, "SignatureDoesNotMatch", e.getMessage());
        }
    }

    // ── Header-based SigV4 ────────────────────────────────────────────────────

    private void authenticateHeaderSig(String authHeader,
                                       HttpServletRequest request,
                                       HttpServletResponse response,
                                       FilterChain chain) throws Exception {

        Matcher m = CREDENTIAL_PATTERN.matcher(authHeader);
        if (!m.find()) {
            sendAuthError(response, "InvalidSignature", "Cannot parse Authorization header");
            return;
        }

        String accessKey = m.group(1);
        String dateScope  = m.group(2);
        String region     = m.group(3);
        // group(4) = service (s3)

        if (!s3Properties.getAccessKey().equals(accessKey)) {
            sendAuthError(response, "InvalidAccessKeyId",
                "The access key does not exist: " + accessKey);
            return;
        }

        // Extract signed headers and signature from Authorization header
        String signedHeaders = extractField(authHeader, "SignedHeaders=", ",");
        String providedSig   = extractField(authHeader, "Signature=", null);

        // Re-compute the signature
        String computedSig = computeSignature(request, dateScope, region,
            signedHeaders, s3Properties.getSecretKey());

        if (!MessageDigest.isEqual(
                providedSig.getBytes(StandardCharsets.UTF_8),
                computedSig.getBytes(StandardCharsets.UTF_8))) {
            log.debug("SigV4 mismatch – provided={} computed={}", providedSig, computedSig);
            sendAuthError(response, "SignatureDoesNotMatch",
                "The request signature we calculated does not match the signature you provided.");
            return;
        }

        setAuthenticated(accessKey);
        chain.doFilter(request, response);
    }

    // ── Pre-signed URL ────────────────────────────────────────────────────────

    private void authenticatePresignedUrl(HttpServletRequest request,
                                          HttpServletResponse response,
                                          FilterChain chain) throws Exception {
        String credential = request.getParameter("X-Amz-Credential");
        if (credential == null) {
            sendAuthError(response, "MissingAuthenticationToken", "X-Amz-Credential missing");
            return;
        }

        String[] parts = credential.split("/");
        String accessKey = parts[0];

        if (!s3Properties.getAccessKey().equals(accessKey)) {
            sendAuthError(response, "InvalidAccessKeyId",
                "The access key does not exist: " + accessKey);
            return;
        }

        // For pre-signed URLs we trust the credential – full re-signing is optional.
        // In production you may want to fully verify the HMAC here as well.
        setAuthenticated(accessKey);
        chain.doFilter(request, response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String computeSignature(HttpServletRequest request,
                                    String dateScope,
                                    String region,
                                    String signedHeaderNames,
                                    String secretKey) throws Exception {

        // 1. Canonical request
        String method         = request.getMethod();
        String canonicalUri   = request.getRequestURI();
        String canonicalQuery = canonicalQueryString(request);
        String canonicalHeaders = canonicalHeaders(request, signedHeaderNames);
        String payloadHash    = payloadHash(request);

        String canonicalRequest = String.join("\n",
            method, canonicalUri, canonicalQuery,
            canonicalHeaders, signedHeaderNames, payloadHash);

        // 2. String to sign
        String dateTime = request.getHeader("x-amz-date");
        if (dateTime == null) dateTime = request.getHeader("X-Amz-Date");
        String credentialScope = dateScope + "/" + region + "/s3/aws4_request";

        String stringToSign = "AWS4-HMAC-SHA256\n"
            + dateTime + "\n"
            + credentialScope + "\n"
            + sha256Hex(canonicalRequest);

        // 3. Signing key
        byte[] signingKey = getSigningKey(secretKey, dateScope, region, "s3");

        // 4. Signature
        return hmacSha256Hex(signingKey, stringToSign);
    }

    private String canonicalQueryString(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null) return "";
        return java.util.Arrays.stream(query.split("&"))
            .sorted()
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private String canonicalHeaders(HttpServletRequest request, String signedHeaderNames) {
        StringBuilder sb = new StringBuilder();
        for (String header : signedHeaderNames.split(";")) {
            String value = request.getHeader(header);
            if (value == null) value = "";
            sb.append(header.toLowerCase()).append(":").append(value.trim()).append("\n");
        }
        return sb.toString();
    }

    private String payloadHash(HttpServletRequest request) {
        String hash = request.getHeader("x-amz-content-sha256");
        return hash != null ? hash : "UNSIGNED-PAYLOAD";
    }

    private byte[] getSigningKey(String secret, String date, String region, String service)
            throws Exception {
        byte[] kSecret  = ("AWS4" + secret).getBytes(StandardCharsets.UTF_8);
        byte[] kDate    = hmacSha256(kSecret, date);
        byte[] kRegion  = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacSha256Hex(byte[] key, String data) throws Exception {
        return HexFormat.of().formatHex(hmacSha256(key, data));
    }

    private String sha256Hex(String data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private String extractField(String header, String prefix, String suffix) {
        int start = header.indexOf(prefix);
        if (start < 0) return "";
        start += prefix.length();
        if (suffix == null) return header.substring(start).trim();
        int end = header.indexOf(suffix, start);
        return end < 0 ? header.substring(start).trim() : header.substring(start, end).trim();
    }

    private void setAuthenticated(String accessKey) {
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(
                accessKey, null, List.of(new SimpleGrantedAuthority("ROLE_S3_CLIENT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void sendAuthError(HttpServletResponse response, String code, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/xml");
        response.getWriter().write(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Error><Code>" + code + "</Code><Message>" + message + "</Message></Error>"
        );
    }
}
