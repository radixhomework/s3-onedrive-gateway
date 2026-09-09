package io.github.radixhomework.s3onedrive.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeySanitizerTest {

    @Test
    void plainKeyIsUnchanged() {
        assertThat(KeySanitizer.sanitize("docs/2024/report-final.pdf"))
            .isEqualTo("docs/2024/report-final.pdf");
    }

    @Test
    void forbiddenCharactersArePercentEncoded() {
        assertThat(KeySanitizer.sanitize("a:b<c>d*e?f\"g|h"))
            .isEqualTo("a%3Ab%3Cc%3Ed%2Ae%3Ff%22g%7Ch");
    }

    @Test
    void percentIsEncodedFirstSoMappingIsInjective() {
        // a literal "%3A" must never collide with an encoded ":"
        assertThat(KeySanitizer.sanitize("50%25")).isEqualTo("50%2525");
        assertThat(KeySanitizer.sanitize("50%3A")).isEqualTo("50%253A");
        assertThat(KeySanitizer.sanitize("50:")).isEqualTo("50%3A");
        assertThat(KeySanitizer.sanitize("50%253A")).isNotEqualTo(KeySanitizer.sanitize("50:"));
    }

    @Test
    void controlCharactersAreEncoded() {
        assertThat(KeySanitizer.sanitize("a\u0007b")).isEqualTo("a%07b");
    }

    @Test
    void trailingDotsAndSpacesAreEncoded() {
        assertThat(KeySanitizer.sanitize("report.")).isEqualTo("report%2E");
        assertThat(KeySanitizer.sanitize("report..")).isEqualTo("report%2E%2E");
        assertThat(KeySanitizer.sanitize("data .")).isEqualTo("data%20%2E");
    }

    @Test
    void leadingSpacesAreEncoded() {
        assertThat(KeySanitizer.sanitize(" x")).isEqualTo("%20x");
        assertThat(KeySanitizer.sanitize("  x")).isEqualTo("%20%20x");
        // internal spaces are preserved
        assertThat(KeySanitizer.sanitize("my file.txt")).isEqualTo("my file.txt");
    }

    @Test
    void reservedDeviceNamesAreNeutralised() {
        assertThat(KeySanitizer.sanitize("CON")).isEqualTo("CO%4E");
        assertThat(KeySanitizer.sanitize("con.txt")).isEqualTo("co%4E.txt");
        assertThat(KeySanitizer.sanitize("LPT1.log")).isEqualTo("LPT%31.log");
        // normal names are untouched
        assertThat(KeySanitizer.sanitize("console.txt")).isEqualTo("console.txt");
    }

    @Test
    void slashesArePreservedAsSeparators() {
        assertThat(KeySanitizer.sanitize("a/b c/d:e.txt")).isEqualTo("a/b c/d%3Ae.txt");
    }

    @Test
    void backslashIsEncoded() {
        assertThat(KeySanitizer.sanitize("a\\b")).isEqualTo("a%5Cb");
    }
}
