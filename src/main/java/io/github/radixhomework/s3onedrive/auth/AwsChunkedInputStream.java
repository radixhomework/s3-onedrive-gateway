package io.github.radixhomework.s3onedrive.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Decodes the "aws-chunked" content encoding S3 clients use for streaming SigV4
 * payloads (x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD[-TRAILER]).
 *
 * Frame format:
 * <pre>
 *   &lt;hex-size&gt;[;chunk-signature=&lt;64 hex&gt;]\r\n
 *   &lt;size bytes of payload&gt;\r\n
 *   ...
 *   0[;chunk-signature=&lt;64 hex&gt;]\r\n
 *   [trailer headers, e.g. x-amz-checksum-*]\r\n
 *   \r\n
 * </pre>
 *
 * When a signing key and seed signature are provided, each chunk signature is
 * verified against the chained SigV4 computation (seed → chunk 1 → chunk 2 …);
 * a mismatch aborts the stream with an IOException so the request never reaches
 * the controller layer with tampered content.
 */
public class AwsChunkedInputStream extends InputStream {

    private final InputStream in;
    private final byte[] signingKey;   // null → decoding only, no verification
    private final String amzDate;
    private final String credentialScope;
    private String previousSignature;

    private byte[] currentChunk;
    private int chunkPos;
    private boolean done;

    public AwsChunkedInputStream(InputStream in, byte[] signingKey,
                                 String amzDate, String credentialScope,
                                 String seedSignature) {
        this.in = in;
        this.signingKey = signingKey;
        this.amzDate = amzDate;
        this.credentialScope = credentialScope;
        this.previousSignature = seedSignature;
    }

    @Override
    public int read() throws IOException {
        if (done) return -1;
        fillChunk();
        if (done) return -1;
        return currentChunk[chunkPos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (done) return -1;
        fillChunk();
        if (done) return -1;
        int n = Math.min(len, currentChunk.length - chunkPos);
        System.arraycopy(currentChunk, chunkPos, b, off, n);
        chunkPos += n;
        return n;
    }

    private void fillChunk() throws IOException {
        if (currentChunk != null && chunkPos < currentChunk.length) return;
        currentChunk = null;
        chunkPos = 0;

        String header = readLine();
        if (header == null || header.isEmpty()) {
            done = true;
            return;
        }
        int semi = header.indexOf(';');
        int size = Integer.parseUnsignedInt((semi < 0 ? header : header.substring(0, semi)).trim(), 16);

        if (size == 0) {
            verifySignature(extractChunkSignature(header), new byte[0]);
            consumeTrailer();
            done = true;
            return;
        }

        byte[] data = in.readNBytes(size);
        if (data.length < size) throw new IOException("Truncated aws-chunked stream");
        verifySignature(extractChunkSignature(header), data);

        int cr = in.read();
        int lf = in.read();
        if (cr != '\r' || lf != '\n') throw new IOException("Malformed aws-chunked framing");
        currentChunk = data;
    }

    private static String extractChunkSignature(String header) {
        int idx = header.indexOf("chunk-signature=");
        return idx < 0 ? null : header.substring(idx + "chunk-signature=".length()).trim();
    }

    private void verifySignature(String provided, byte[] data) throws IOException {
        if (signingKey == null) return;

        String expected = SigV4.hmacSha256Hex(signingKey,
            SigV4.CHUNK_STRING_TO_SIGN + "\n" + amzDate + "\n" + credentialScope + "\n"
                + previousSignature + "\n" + SigV4.sha256Hex(new byte[0]) + "\n"
                + SigV4.sha256Hex(data));
        previousSignature = expected;

        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Chunk signature verification failed");
        }
    }

    /** Reads trailer header lines up to the terminating empty line (or EOF). */
    private void consumeTrailer() throws IOException {
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            // checksum trailers (x-amz-checksum-*) are not enforced
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int previous = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (previous == '\r' && c == '\n') {
                byte[] bytes = buf.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            buf.write(c);
            previous = c;
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
    }
}
