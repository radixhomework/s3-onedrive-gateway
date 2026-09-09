package io.github.radixhomework.s3onedrive.multipart;

import io.github.radixhomework.s3onedrive.config.MultipartProperties;
import io.github.radixhomework.s3onedrive.multipart.MultipartUploadStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MultipartUploadStoreTest {

    @TempDir
    Path tempDir;

    private MultipartUploadStore store;

    @BeforeEach
    void setUp() throws IOException {
        MultipartProperties props = new MultipartProperties();
        props.setTempDir(tempDir.toString());
        store = new MultipartUploadStore(props);
    }

    @Test
    void createAndRetrieveUpload() {
        var upload = store.create("my-bucket", "my/key.bin", "application/octet-stream");

        assertThat(upload.getUploadId()).isNotBlank();
        assertThat(upload.getBucket()).isEqualTo("my-bucket");
        assertThat(upload.getKey()).isEqualTo("my/key.bin");

        var retrieved = store.get(upload.getUploadId());
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getUploadId()).isEqualTo(upload.getUploadId());
    }

    @Test
    void storePart_writesFileAndReturnsEtag() throws IOException {
        var upload = store.create("b", "k", null);
        byte[] data = "hello world".getBytes();

        String etag = store.storePart(upload.getUploadId(), 1, data);

        assertThat(etag).isNotBlank();
        assertThat(upload.getParts()).containsKey(1);
        assertThat(upload.getParts().get(1).getSize()).isEqualTo(data.length);
    }

    @Test
    void getOrderedParts_sortsByPartNumber() throws IOException {
        var upload = store.create("b", "k", null);
        store.storePart(upload.getUploadId(), 3, "part3".getBytes());
        store.storePart(upload.getUploadId(), 1, "part1".getBytes());
        store.storePart(upload.getUploadId(), 2, "part2".getBytes());

        List<MultipartUploadStore.Part> parts =
            store.getOrderedParts(upload.getUploadId(), List.of(1, 2, 3));

        assertThat(parts).extracting(MultipartUploadStore.Part::getPartNumber)
            .containsExactly(1, 2, 3);
    }

    @Test
    void abortDeletesTempFiles() throws IOException {
        var upload = store.create("b", "k", null);
        store.storePart(upload.getUploadId(), 1, "data".getBytes());
        var partFile = upload.getParts().get(1).getTempFile();

        assertThat(partFile).exists();

        store.abort(upload.getUploadId());

        assertThat(partFile).doesNotExist();
        assertThat(store.get(upload.getUploadId())).isNull();
    }

    @Test
    void getMissingUploadReturnsNull() {
        assertThat(store.get("non-existent-id")).isNull();
    }

    @Test
    void storePartWithUnknownUploadIdThrows() {
        assertThatThrownBy(() -> store.storePart("bad-id", 1, new byte[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown uploadId");
    }
}
