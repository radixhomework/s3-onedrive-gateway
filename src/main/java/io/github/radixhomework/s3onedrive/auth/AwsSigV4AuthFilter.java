package io.github.radixhomework.s3onedrive.auth;

import io.github.radixhomework.s3onedrive.config.S3Properties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates AWS Signature Version 4 on every request.
 *
 * Supports:
 *  - Authorization header style  (AWS4-HMAC-SHA256 Credential=...), including
 *    streaming "aws-chunked" payloads whose chunk framing is decoded (and its
 *    per-chunk signatures verified) transparently for the controllers;
 *  - Pre-signed URL style (?X-Amz-Algorithm=AWS4-HMAC-SHA256&...) with full
 *    HMAC re-computation and expiry validation.
 */
@Slf4j
@Component
public class AwsSigV4AuthFilter extends OncePerRequestFilter {

    private static final Pattern CREDENTIAL_PATTERN =
        Pattern.compile("Credential=([^/]+)/([^/]+)/([^/]+)/([^/]+)/aws4_request");

    /** Maximum allowed drift between the request date and the server clock. */
    private static final long CLOCK_SKEW_SECONDS = 900;

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

            if (authHeader != null && authHeader.startsWith(SigV4.ALGORITHM)) {
                authenticateHeaderSig(authHeader, request, response, filterChain);
            } else if (SigV4.ALGORITHM.equals(xAmzAlgorithm)) {
                authenticatePresignedUrl(request, response, filterChain);
            } else {
                sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                    "MissingAuthenticationToken", "No valid AWS authentication found");
            }
        } catch (Exception e) {
            log.warn("Authentication error: {}", e.getMessage());
            sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                "SignatureDoesNotMatch", e.getMessage());
        }
    }

    // ── Header-based SigV4 ────────────────────────────────────────────────────

    private void authenticateHeaderSig(String authHeader,
                                       HttpServletRequest request,
                                       HttpServletResponse response,
                                       FilterChain chain) throws Exception {

        Matcher m = CREDENTIAL_PATTERN.matcher(authHeader);
        if (!m.find()) {
            sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                "InvalidSignature", "Cannot parse Authorization header");
            return;
        }

        String accessKey  = m.group(1);
        String dateScope  = m.group(2);
        String region     = m.group(3);
        // group(4) = service (s3)

        if (!s3Properties.getAccessKey().equals(accessKey)) {
            sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                "InvalidAccessKeyId", "The access key does not exist: " + accessKey);
            return;
        }
        if (!s3Properties.getRegion().equals(region)) {
            sendAuthError(response, HttpServletResponse.SC_BAD_REQUEST,
                "AuthorizationHeaderMalformed",
                "The region '" + region + "' is wrong; expecting '" + s3Properties.getRegion() + "'");
            return;
        }

        String amzDate = firstNonNull(request.getHeader("x-amz-date"), request.getHeader("X-Amz-Date"));
        if (amzDate == null || !isWithinClockSkew(amzDate)) {
            sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                "AccessDenied", "Request time is too skewed from server time (max "
                    + CLOCK_SKEW_SECONDS + "s)");
            return;
        }

        String signedHeaders = extractField(authHeader, "SignedHeaders=", ",");
        String providedSig   = extractField(authHeader, "Signature=", null);
        String payloadHash   = payloadHash(request);

        String computedSig = computeSignature(request, dateScope, region, amzDate,
            signedHeaders, payloadHash, s3Properties.getSecretKey());

        if (!MessageDigest.isEqual(
                providedSig.getBytes(StandardCharsets.UTF_8),
                computedSig.getBytes(StandardCharsets.UTF_8))) {
            log.debug("SigV4 mismatch – provided={} computed={}", providedSig, computedSig);
            sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                "SignatureDoesNotMatch",
                "The request signature we calculated does not match the signature you provided.");
            return;
        }

        setAuthenticated(accessKey);
        HttpServletRequest wrapped = wrapAwsChunked(request, payloadHash, providedSig, dateScope, region, amzDate);
        chain.doFilter(wrapped, response);
    }

    // ── Pre-signed URL ────────────────────────────────────────────────────────

    private void authenticatePresignedUrl(HttpServletRequest request,
                                          HttpServletResponse response,
                                          FilterChain chain) throws Exception {

        String credential    = request.getParameter("X-Amz-Credential");
        String amzDate       = request.getParameter("X-Amz-Date");
        String expiresStr    = request.getParameter("X-Amz-Expires");
        String signedHeaders = request.getParameter("X-Amz-SignedHeaders");
        String providedSig   = request.getParameter("X-Amz-Signature");

        if (credential == null || amzDate == null || expiresStr == null
            || signedHeaders == null || providedSig == null) {
            sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                "MissingAuthenticationToken", "Incomplete pre-signed URL parameters");
            return;
        }

        String[] parts = credential.split("/");
        if (parts.length < 3) {
            sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                "AuthorizationHeaderMalformed", "Cannot parse X-Amz-Credential");
            return;
        }
        String accessKey = parts[0];
        String dateScope = parts[1];
        String region    = parts[2];

        if (!s3Properties.getAccessKey().equals(accessKey)) {
            sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                "InvalidAccessKeyId", "The access key does not exist: " + accessKey);
            return;
        }
        if (!s3Properties.getRegion().equals(region)) {
            sendAuthError(response, HttpServletResponse.SC_BAD_REQUEST,
                "AuthorizationHeaderMalformed",
                "The region '" + region + "' is wrong; expecting '" + s3Properties.getRegion() + "'");
            return;
        }

        // Validity window: [date – skew, date + expires]
        Instant requestTime = SigV4.parseAmzDate(amzDate);
        Instant now = Instant.now();
        long expires;
        try {
            expires = Long.parseLong(expiresStr);
        } catch (NumberFormatException e) {
            sendAuthError(response, HttpServletResponse.SC_BAD_REQUEST,
                "AuthorizationHeaderMalformed", "Invalid X-Amz-Expires");
            return;
        }
        if (now.isBefore(requestTime.minus(CLOCK_SKEW_SECONDS, ChronoUnit.SECONDS))
            || now.isAfter(requestTime.plus(expires, ChronoUnit.SECONDS))) {
            sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                "AccessDenied", "Request has expired");
            return;
        }

        // Full re-computation of the signature over the canonical request
        String canonicalQuery = SigV4.canonicalQueryString(request.getQueryString(), Set.of("X-Amz-Signature"));
        String canonicalHeaders = canonicalHeaders(request, signedHeaders);

        String canonicalRequest = String.join("\n",
            request.getMethod(),
            request.getRequestURI(),
            canonicalQuery,
            canonicalHeaders,
            signedHeaders.toLowerCase(),
            SigV4.UNSIGNED_PAYLOAD);

        String stringToSign = String.join("\n",
            SigV4.ALGORITHM,
            amzDate,
            dateScope + "/" + region + "/s3/aws4_request",
            SigV4.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        String computedSig = SigV4.hmacSha256Hex(
            SigV4.signingKey(s3Properties.getSecretKey(), dateScope, region, "s3"), stringToSign);

        if (!MessageDigest.isEqual(
                providedSig.getBytes(StandardCharsets.UTF_8),
                computedSig.getBytes(StandardCharsets.UTF_8))) {
            log.debug("Pre-signed SigV4 mismatch – provided={} computed={}", providedSig, computedSig);
            sendAuthError(response, HttpServletResponse.SC_FORBIDDEN,
                "SignatureDoesNotMatch",
                "The request signature we calculated does not match the signature you provided.");
            return;
        }

        setAuthenticated(accessKey);
        HttpServletRequest wrapped = wrapAwsChunked(request, null, providedSig, dateScope, region, amzDate);
        chain.doFilter(wrapped, response);
    }

    // ── aws-chunked decoding ──────────────────────────────────────────────────

    /**
     * Wraps the request with a stream that decodes aws-chunked framing (and verifies
     * chunk signatures) so controllers read plain object data.
     */
    private HttpServletRequest wrapAwsChunked(HttpServletRequest request,
                                              String payloadHash,
                                              String seedSignature,
                                              String dateScope,
                                              String region,
                                              String amzDate) throws IOException {
        String contentEncoding = request.getHeader("Content-Encoding");
        boolean chunked = (payloadHash != null && payloadHash.startsWith(SigV4.STREAMING_PAYLOAD))
            || (contentEncoding != null && contentEncoding.contains("aws-chunked"));
        if (!chunked) return request;

        byte[] signingKey = SigV4.signingKey(s3Properties.getSecretKey(), dateScope, region, "s3");
        String credentialScope = dateScope + "/" + region + "/s3/aws4_request";
        AwsChunkedInputStream decoded = new AwsChunkedInputStream(
            request.getInputStream(), signingKey, amzDate, credentialScope, seedSignature);

        return new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                return new DecodedServletInputStream(decoded);
            }

            @Override
            public int getContentLength() {
                long len = getContentLengthLong();
                return len > Integer.MAX_VALUE ? -1 : (int) len;
            }

            @Override
            public long getContentLengthLong() {
                String decodedLength = getHeader("x-amz-decoded-content-length");
                try {
                    return decodedLength != null ? Long.parseLong(decodedLength) : -1;
                } catch (NumberFormatException e) {
                    return -1;
                }
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        };
    }

    private static final class DecodedServletInputStream extends ServletInputStream {
        private final InputStream in;

        private DecodedServletInputStream(InputStream in) {
            this.in = in;
        }

        @Override public int read() throws IOException { return in.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return in.read(b, off, len); }
        @Override public boolean isFinished() { try { return in.available() == 0; } catch (IOException e) { return true; } }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { /* non-async streaming */ }
    }

    // ── Signature computation (header auth) ───────────────────────────────────

    private String computeSignature(HttpServletRequest request,
                                    String dateScope,
                                    String region,
                                    String amzDate,
                                    String signedHeaderNames,
                                    String payloadHash,
                                    String secretKey) {

        String canonicalRequest = String.join("\n",
            request.getMethod(),
            request.getRequestURI(),
            SigV4.canonicalQueryString(request.getQueryString(), null),
            canonicalHeaders(request, signedHeaderNames),
            signedHeaderNames,
            payloadHash);

        String stringToSign = String.join("\n",
            SigV4.ALGORITHM,
            amzDate,
            dateScope + "/" + region + "/s3/aws4_request",
            SigV4.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        return SigV4.hmacSha256Hex(SigV4.signingKey(secretKey, dateScope, region, "s3"), stringToSign);
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
        return hash != null ? hash : SigV4.UNSIGNED_PAYLOAD;
    }

    private boolean isWithinClockSkew(String amzDate) {
        try {
            Instant requestTime = SigV4.parseAmzDate(amzDate);
            long skew = Math.abs(ChronoUnit.SECONDS.between(Instant.now(), requestTime));
            return skew <= CLOCK_SKEW_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
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

    private void sendAuthError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/xml");
        response.getWriter().write(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Error><Code>" + xmlEscape(code) + "</Code><Message>" + xmlEscape(message) + "</Message></Error>"
        );
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
