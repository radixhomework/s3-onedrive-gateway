package io.github.radixhomework.s3onedrive.auth;

import io.github.radixhomework.s3onedrive.config.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwsSigV4AuthFilterTest {

    private static final String ACCESS_KEY = "testkey";
    private static final String SECRET_KEY = "testsecret";
    private static final String REGION = "us-east-1";

    private AwsSigV4AuthFilter filter;

    @BeforeEach
    void setUp() {
        S3Properties props = new S3Properties();
        props.setAccessKey(ACCESS_KEY);
        props.setSecretKey(SECRET_KEY);
        props.setRegion(REGION);
        filter = new AwsSigV4AuthFilter(props);
    }

    // ── Basic behaviour ───────────────────────────────────────────────────────

    @Test
    void healthEndpointBypassesAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isNotEqualTo(403);
    }

    @Test
    void missingAuthHeaderReturns403() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/my-bucket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("MissingAuthenticationToken");
    }

    @Test
    void wrongAccessKeyReturns403() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/my-bucket");
        request.addHeader("Authorization",
            "AWS4-HMAC-SHA256 Credential=WRONGKEY/" + dateScope() + "/" + REGION + "/s3/aws4_request, " +
            "SignedHeaders=host, Signature=abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("InvalidAccessKeyId");
    }

    // ── Header-based signature ────────────────────────────────────────────────

    @Test
    void validHeaderSignaturePasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/my-bucket");
        request.addHeader("host", "localhost");
        signHeaderAuth(request, dateScope(), nowAmzDate(), "host", SigV4.sha256Hex(new byte[0]));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void tamperedHeaderSignatureReturns403() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/my-bucket");
        request.addHeader("host", "localhost");
        signHeaderAuth(request, dateScope(), nowAmzDate(), "host", SigV4.sha256Hex(new byte[0]));
        String auth = request.getHeader("Authorization");
        request.removeHeader("Authorization");
        request.addHeader("Authorization", auth.substring(0, auth.length() - 4) + "beef");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("SignatureDoesNotMatch");
    }

    @Test
    void staleRequestDateReturns403() throws Exception {
        // Properly signed, but dated far in the past → clock-skew rejection
        String oldDate = "20240101T000000Z";
        String oldScope = "20240101";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/my-bucket");
        request.addHeader("host", "localhost");
        signHeaderAuth(request, oldScope, oldDate, "host", SigV4.sha256Hex(new byte[0]));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("AccessDenied");
    }

    @Test
    void wrongRegionReturns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/my-bucket");
        request.addHeader("host", "localhost");
        request.addHeader("x-amz-date", nowAmzDate());
        request.addHeader("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY + "/"
            + dateScope() + "/eu-west-1/s3/aws4_request, SignedHeaders=host, Signature=" + "a".repeat(64));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("AuthorizationHeaderMalformed");
    }

    // ── Pre-signed URLs ───────────────────────────────────────────────────────

    @Test
    void presignedUrlWithValidSignaturePasses() throws Exception {
        MockHttpServletRequest request = presignedRequest("GET", "/my-bucket/my-key",
            "3600", nowAmzDate(), dateScope());

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void presignedUrlWithTamperedSignatureReturns403() throws Exception {
        MockHttpServletRequest request = presignedRequest("GET", "/my-bucket/my-key",
            "3600", nowAmzDate(), dateScope());
        request.setParameter("X-Amz-Signature", "fakesig");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("SignatureDoesNotMatch");
    }

    @Test
    void presignedUrlExpiredReturns403() throws Exception {
        MockHttpServletRequest request = presignedRequest("GET", "/my-bucket/my-key",
            "3600", "20240101T000000Z", "20240101");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("AccessDenied");
    }

    // ── aws-chunked streaming payload ─────────────────────────────────────────

    @Test
    void awsChunkedBodyIsDecodedAndVerified() throws Exception {
        byte[] part1 = "Hello ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "aws-chunked world".getBytes(StandardCharsets.UTF_8);
        String amzDate = nowAmzDate();
        String scope = dateScope();

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/my-bucket/file.bin");
        request.addHeader("host", "localhost");
        String seedSignature = signHeaderAuth(request, scope, amzDate, "host", SigV4.STREAMING_PAYLOAD);
        request.addHeader("x-amz-decoded-content-length", String.valueOf(part1.length + part2.length));
        request.setContent(buildChunkedBody(seedSignature, amzDate, scope, part1, part2));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("request should reach the chain").isNotNull();
        byte[] decoded = readAll(chain.getRequest().getInputStream());
        byte[] expected = new byte[part1.length + part2.length];
        System.arraycopy(part1, 0, expected, 0, part1.length);
        System.arraycopy(part2, 0, expected, part1.length, part2.length);
        assertThat(decoded).isEqualTo(expected);
    }

    @Test
    void tamperedChunkSignatureFailsVerification() throws Exception {
        byte[] part1 = "Hello ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "tampered".getBytes(StandardCharsets.UTF_8);
        String amzDate = nowAmzDate();
        String scope = dateScope();

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/my-bucket/file.bin");
        request.addHeader("host", "localhost");
        String seedSignature = signHeaderAuth(request, scope, amzDate, "host", SigV4.STREAMING_PAYLOAD);
        byte[] body = buildChunkedBody(seedSignature, amzDate, scope, part1, part2);
        // corrupt the first payload byte of chunk 1 without touching the framing:
        // frame = "<hex size>;chunk-signature=<64 hex>\r\n" then the raw data
        int chunk1DataStart = Integer.toHexString(part1.length).length()
            + ";chunk-signature=".length() + 64 + 2;
        body[chunk1DataStart] ^= 0x01;
        request.setContent(body);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThatThrownBy(() -> readAll(chain.getRequest().getInputStream()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Chunk signature");
    }

    // ── Signing helpers (mirror the SigV4 spec) ───────────────────────────────

    private String signHeaderAuth(MockHttpServletRequest request, String dateScope, String amzDate,
                                  String signedHeaders, String payloadHash) {
        String canonicalRequest = String.join("\n",
            request.getMethod(),
            request.getRequestURI(),
            SigV4.canonicalQueryString(request.getQueryString(), null),
            canonicalHeaders(request, signedHeaders),
            signedHeaders,
            payloadHash);
        String stringToSign = String.join("\n",
            SigV4.ALGORITHM,
            amzDate,
            dateScope + "/" + REGION + "/s3/aws4_request",
            SigV4.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        String signature = SigV4.hmacSha256Hex(SigV4.signingKey(SECRET_KEY, dateScope, REGION, "s3"), stringToSign);

        request.addHeader("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY + "/"
            + dateScope + "/" + REGION + "/s3/aws4_request, SignedHeaders=" + signedHeaders
            + ", Signature=" + signature);
        request.addHeader("x-amz-date", amzDate);
        request.addHeader("x-amz-content-sha256", payloadHash);
        return signature;
    }

    private MockHttpServletRequest presignedRequest(String method, String uri, String expires,
                                                    String amzDate, String dateScope) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("host", "localhost");
        request.setParameter("X-Amz-Algorithm", SigV4.ALGORITHM);
        request.setParameter("X-Amz-Credential", ACCESS_KEY + "/" + dateScope + "/" + REGION + "/s3/aws4_request");
        request.setParameter("X-Amz-Date", amzDate);
        request.setParameter("X-Amz-Expires", expires);
        request.setParameter("X-Amz-SignedHeaders", "host");

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            for (String v : e.getValue()) {
                if (query.length() > 0) query.append('&');
                query.append(SigV4.uriEncode(e.getKey())).append('=').append(SigV4.uriEncode(v));
            }
        }
        request.setQueryString(query.toString());

        String canonicalQuery = SigV4.canonicalQueryString(query.toString(), java.util.Set.of("X-Amz-Signature"));
        String canonicalRequest = String.join("\n",
            method,
            uri,
            canonicalQuery,
            canonicalHeaders(request, "host"),
            "host",
            SigV4.UNSIGNED_PAYLOAD);
        String stringToSign = String.join("\n",
            SigV4.ALGORITHM,
            amzDate,
            dateScope + "/" + REGION + "/s3/aws4_request",
            SigV4.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        String signature = SigV4.hmacSha256Hex(SigV4.signingKey(SECRET_KEY, dateScope, REGION, "s3"), stringToSign);
        request.setParameter("X-Amz-Signature", signature);
        return request;
    }

    private byte[] buildChunkedBody(String seedSignature, String amzDate, String dateScope, byte[]... chunks) {
        String credentialScope = dateScope + "/" + REGION + "/s3/aws4_request";
        byte[] key = SigV4.signingKey(SECRET_KEY, dateScope, REGION, "s3");
        StringBuilder body = new StringBuilder();
        String previous = seedSignature;
        for (byte[] chunk : chunks) {
            String sig = chunkSignature(previous, chunk, key, amzDate, credentialScope);
            body.append(Integer.toHexString(chunk.length)).append(";chunk-signature=").append(sig)
                .append("\r\n");
            body.append(new String(chunk, StandardCharsets.ISO_8859_1)).append("\r\n");
            previous = sig;
        }
        String finalSig = chunkSignature(previous, new byte[0], key, amzDate, credentialScope);
        body.append("0;chunk-signature=").append(finalSig).append("\r\n\r\n");
        return body.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private String chunkSignature(String previousSignature, byte[] data, byte[] signingKey,
                                  String amzDate, String credentialScope) {
        String stringToSign = String.join("\n",
            SigV4.CHUNK_STRING_TO_SIGN,
            amzDate,
            credentialScope,
            previousSignature,
            SigV4.sha256Hex(new byte[0]),
            SigV4.sha256Hex(data));
        return SigV4.hmacSha256Hex(signingKey, stringToSign);
    }

    private String canonicalHeaders(MockHttpServletRequest request, String signedHeaders) {
        StringBuilder sb = new StringBuilder();
        for (String name : signedHeaders.split(";")) {
            String value = request.getHeader(name);
            if (value == null) value = "";
            sb.append(name.toLowerCase()).append(":").append(value.trim()).append("\n");
        }
        return sb.toString();
    }

    private static String dateScope() {
        return SigV4.formatAmzDate(Instant.now()).substring(0, 8);
    }

    private static String nowAmzDate() {
        return SigV4.formatAmzDate(Instant.now());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }
}
