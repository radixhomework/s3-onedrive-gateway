package io.github.radixhomework.s3onedrive.auth;

import io.github.radixhomework.s3onedrive.auth.AwsSigV4AuthFilter;
import io.github.radixhomework.s3onedrive.config.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AwsSigV4AuthFilterTest {

    private AwsSigV4AuthFilter filter;

    @BeforeEach
    void setUp() {
        S3Properties props = new S3Properties();
        props.setAccessKey("testkey");
        props.setSecretKey("testsecret");
        props.setRegion("us-east-1");
        filter = new AwsSigV4AuthFilter(props);
    }

    @Test
    void healthEndpointBypassesAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // Chain was invoked → no 403
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
            "AWS4-HMAC-SHA256 Credential=WRONGKEY/20240101/us-east-1/s3/aws4_request, " +
            "SignedHeaders=host, Signature=abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("InvalidAccessKeyId");
    }

    @Test
    void presignedUrlWithCorrectKeyPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/my-bucket/my-key");
        request.addParameter("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        request.addParameter("X-Amz-Credential", "testkey/20240101/us-east-1/s3/aws4_request");
        request.addParameter("X-Amz-Date", "20240101T000000Z");
        request.addParameter("X-Amz-Expires", "3600");
        request.addParameter("X-Amz-SignedHeaders", "host");
        request.addParameter("X-Amz-Signature", "fakesig");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // Pre-signed URL only validates access key, not full HMAC in this impl
        assertThat(chain.getRequest()).isNotNull();
    }
}
