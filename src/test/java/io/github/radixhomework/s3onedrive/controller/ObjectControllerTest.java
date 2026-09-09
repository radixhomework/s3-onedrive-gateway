package io.github.radixhomework.s3onedrive.controller;

import io.github.radixhomework.s3onedrive.auth.AwsSigV4AuthFilter;
import io.github.radixhomework.s3onedrive.auth.OneDriveTokenService;
import io.github.radixhomework.s3onedrive.service.OneDriveService;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItem;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItemList;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ObjectControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OneDriveService driveService;

    @MockitoBean
    private OneDriveTokenService tokenService;

    // SigV4 authentication is covered by AwsSigV4AuthFilterTest;
    // here the filter is bypassed and @WithMockUser provides authentication.
    @MockitoBean
    private AwsSigV4AuthFilter sigV4AuthFilter;

    @BeforeEach
    void letSigV4FilterPassThrough() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(sigV4AuthFilter).doFilter(any(ServletRequest.class), any(ServletResponse.class), any(FilterChain.class));
    }

    private static final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    // ── ListObjects ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void listObjects_returnsXml() throws Exception {
        DriveItem file = new DriveItem();
        file.setId("id1");
        file.setName("readme.txt");
        file.setSize(42L);
        file.setLastModifiedDateTime("2024-01-01T00:00:00Z");
        file.setFile(new Object());

        DriveItemList list = new DriveItemList();
        list.setItems(List.of(file));

        when(driveService.listBucketChildren(eq("my-bucket"), any(), any()))
            .thenReturn(list);

        mvc.perform(get("/my-bucket")
                .accept(MediaType.APPLICATION_XML))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andExpect(xpath("/ListBucketResult/Contents/Key").string("readme.txt"));
    }

    // ── GetObject ─────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void getObject_streamsContent() throws Exception {
        DriveItem meta = new DriveItem();
        meta.setId("id1");
        meta.setName("hello.txt");
        meta.setSize(5L);
        meta.setFile(new Object());

        when(driveService.getItemMetadata("my-bucket", "hello.txt")).thenReturn(meta);
        when(driveService.downloadItem("my-bucket", "hello.txt")).thenReturn(
            Flux.just(bufferFactory.wrap("hello".getBytes()))
        );

        mvc.perform(get("/my-bucket/hello.txt"))
            .andExpect(status().isOk())
            .andExpect(content().bytes("hello".getBytes()));
    }

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void getObject_notFound_returns404() throws Exception {
        when(driveService.getItemMetadata("my-bucket", "missing.txt")).thenReturn(null);

        mvc.perform(get("/my-bucket/missing.txt"))
            .andExpect(status().isNotFound())
            .andExpect(xpath("/Error/Code").string("NoSuchKey"));
    }

    // ── PutObject ─────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void putObject_smallFile_callsUploadSmall() throws Exception {
        DriveItem result = new DriveItem();
        result.setETag("\"abc123\"");
        when(driveService.uploadSmall(eq("my-bucket"), eq("file.txt"), any(), any()))
            .thenReturn(result);

        mvc.perform(put("/my-bucket/file.txt")
                .contentType(MediaType.TEXT_PLAIN)
                .content("hello"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"abc123\""));

        verify(driveService).uploadSmall(eq("my-bucket"), eq("file.txt"), any(), any());
    }

    // ── DeleteObject ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void deleteObject_returns204() throws Exception {
        when(driveService.deleteItem("my-bucket", "file.txt")).thenReturn(true);

        mvc.perform(delete("/my-bucket/file.txt"))
            .andExpect(status().isNoContent());
    }

    // ── HeadObject ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void headObject_existingFile_returns200() throws Exception {
        DriveItem meta = new DriveItem();
        meta.setId("id1");
        meta.setSize(100L);
        meta.setFile(new Object());

        when(driveService.getItemMetadata("my-bucket", "file.txt")).thenReturn(meta);

        mvc.perform(head("/my-bucket/file.txt"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void headObject_missing_returns404() throws Exception {
        when(driveService.getItemMetadata("my-bucket", "gone.txt")).thenReturn(null);

        mvc.perform(head("/my-bucket/gone.txt"))
            .andExpect(status().isNotFound());
    }
}
