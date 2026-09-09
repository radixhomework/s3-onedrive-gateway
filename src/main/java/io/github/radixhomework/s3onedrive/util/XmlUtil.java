package io.github.radixhomework.s3onedrive.util;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shared Jackson XML mapper helpers.
 */
public final class XmlUtil {

    private static final XmlMapper MAPPER;

    static {
        MAPPER = XmlMapper.builder()
            .configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true)
            .configure(SerializationFeature.INDENT_OUTPUT, false)
            .build();
    }

    private XmlUtil() {}

    public static void writeXml(OutputStream out, Object value) throws IOException {
        MAPPER.writeValue(out, value);
    }

    public static String toXmlString(Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T readXml(InputStream in, Class<T> type) throws IOException {
        return MAPPER.readValue(in, type);
    }
}
