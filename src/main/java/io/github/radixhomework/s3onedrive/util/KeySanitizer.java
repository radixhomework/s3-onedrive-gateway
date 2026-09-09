package io.github.radixhomework.s3onedrive.util;

import java.util.Set;

/**
 * Maps arbitrary S3 object keys onto file names Microsoft OneDrive accepts.
 *
 * OneDrive (like any Windows-based store) forbids {@code " * : < > ? | / \},
 * control characters, leading/trailing spaces, trailing dots and the reserved
 * device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9).
 *
 * The mapping is deterministic and injective: "%" is encoded first, so an
 * encoded form can never collide with a key that literally contained it.
 */
public final class KeySanitizer {

    private static final Set<String> RESERVED_DEVICE_NAMES = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private KeySanitizer() {}

    /** Sanitizes every path segment of an S3 key; the '/' separators are preserved. */
    public static String sanitize(String key) {
        if (key == null || key.isEmpty()) return key;
        String[] segments = key.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(sanitizeSegment(segments[i]));
        }
        return sb.toString();
    }

    private static String sanitizeSegment(String segment) {
        int length = segment.length();
        if (length == 0) return segment;

        int leadingSpaces = countLeading(segment, ' ');
        int trailingLoose = countTrailing(segment, c -> c == '.' || c == ' ');

        StringBuilder sb = new StringBuilder(length + 8);
        for (int i = 0; i < length; i++) {
            char c = segment.charAt(i);
            boolean isLeadingSpace = c == ' ' && i < leadingSpaces;
            boolean isTrailingLoose = (c == '.' || c == ' ') && i >= length - trailingLoose;
            if (isForbidden(c) || isLeadingSpace || isTrailingLoose) {
                sb.append(percentByte(c));
            } else {
                sb.append(c);
            }
        }

        // Reserved device names (CON, con.txt, LPT1.log ...): encode the last
        // character of the base name. Safe because "%" was encoded first.
        String base = segment.split("\\.", 2)[0];
        if (RESERVED_DEVICE_NAMES.contains(base.toUpperCase())) {
            char last = Character.toUpperCase(base.charAt(base.length() - 1));
            sb.replace(base.length() - 1, base.length(), percentByte(last));
        }

        return sb.toString();
    }

    private static boolean isForbidden(char c) {
        return c == '%' || c == '"' || c == '*' || c == ':' || c == '<' || c == '>'
            || c == '?' || c == '|' || c == '\\' || c < 0x20 || c == 0x7F;
    }

    private static int countLeading(String s, char c) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == c) n++;
        return n;
    }

    private static int countTrailing(String s, java.util.function.IntPredicate chars) {
        int n = 0;
        while (n < s.length() && chars.test(s.charAt(s.length() - 1 - n))) n++;
        return n;
    }

    private static String percentByte(char c) {
        return String.format("%%%02X", (int) c & 0xFF);
    }
}
