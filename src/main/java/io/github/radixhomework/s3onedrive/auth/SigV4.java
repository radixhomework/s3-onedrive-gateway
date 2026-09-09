package io.github.radixhomework.s3onedrive.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HexFormat;

/**
 * Shared AWS Signature Version 4 primitives (hashing, canonicalisation, signing).
 *
 * S3 canonical form details implemented here:
 *  - query parameters are URL-decoded then re-encoded (RFC 3986, unreserved = A-Z a-z 0-9 - . _ ~)
 *    and sorted by encoded name then encoded value;
 *  - "+" in a query string is treated as a literal plus (AWS SDKs never send form encoding);
 *  - x-amz-date values use the "yyyyMMdd'T'HHmmss'Z'" UTC format.
 */
public final class SigV4 {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    public static final String STREAMING_PAYLOAD = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD";
    public static final String CHUNK_STRING_TO_SIGN = "AWS4-HMAC-SHA256-PAYLOAD";

    private static final DateTimeFormatter AMZ_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private SigV4() {}

    // ── Hashing / signing ─────────────────────────────────────────────────────

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static byte[] signingKey(String secret, String date, String region, String service) {
        byte[] kSecret = ("AWS4" + secret).getBytes(StandardCharsets.UTF_8);
        byte[] kDate    = hmacSha256(kSecret, date);
        byte[] kRegion  = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    public static String hmacSha256Hex(byte[] key, String data) {
        return HexFormat.of().formatHex(hmacSha256(key, data));
    }

    public static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 failure", e);
        }
    }

    // ── Dates ─────────────────────────────────────────────────────────────────

    public static Instant parseAmzDate(String amzDate) {
        return LocalDateTime.parse(amzDate, AMZ_DATE_FORMAT).toInstant(ZoneOffset.UTC);
    }

    public static String formatAmzDate(Instant instant) {
        return AMZ_DATE_FORMAT.format(instant.atZone(ZoneOffset.UTC));
    }

    // ── Canonicalisation ──────────────────────────────────────────────────────

    /** RFC 3986 percent-encoding of a query name or value (slashes encoded). */
    public static String uriEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b));
            }
        }
        return sb.toString();
    }

    private static String uriDecode(String s) {
        // "+" is preserved as a literal plus: AWS SDK clients percent-encode spaces as %20
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * Builds the canonical query string: each parameter is decoded, re-encoded and
     * the list sorted by encoded name then encoded value.
     *
     * @param rawQuery     the raw query string from the request (may be null)
     * @param excludeNames parameter names to exclude (e.g. X-Amz-Signature for pre-signed URLs)
     */
    public static String canonicalQueryString(String rawQuery, Set<String> excludeNames) {
        if (rawQuery == null || rawQuery.isBlank()) return "";

        record Pair(String name, String value) {}
        List<Pair> pairs = new ArrayList<>();
        for (String param : rawQuery.split("&")) {
            if (param.isEmpty()) continue;
            int eq = param.indexOf('=');
            String rawName = eq < 0 ? param : param.substring(0, eq);
            String rawValue = eq < 0 ? "" : param.substring(eq + 1);
            String name = uriDecode(rawName);
            if (excludeNames != null && excludeNames.contains(name)) continue;
            pairs.add(new Pair(uriEncode(name), uriEncode(rawValue)));
        }
        pairs.sort(Comparator.comparing(Pair::name).thenComparing(Pair::value));
        return pairs.stream()
            .map(p -> p.name() + "=" + p.value())
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
    }
}
