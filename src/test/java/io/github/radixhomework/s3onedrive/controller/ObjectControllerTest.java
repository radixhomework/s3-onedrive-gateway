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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static DriveItem fileItem(String name, long size, String etag) {
        DriveItem item = new DriveItem();
        item.setId("id-" + name);
        item.setName(name);
        item.setSize(size);
        item.setLastModifiedDateTime("2024-01-01T00:00:00Z");
        item.setETag(etag);
        item.setFile(new Object());
        return item;
    }

    private static ResponseEntity<Flux<DataBuffer>> bodyEntity(String content) {
        return ResponseEntity.ok(Flux.just(bufferFactory.wrap(content.getBytes())));
    }

    // ── ListObjects ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void listObjects_returnsXml() throws Exception {
        DriveItemList list = new DriveItemList();
        list.setItems(List.of(fileItem("readme.txt", 42L, "etag1")));

        when(driveService.listBucketChildren(eq("my-bucket"), any()))
            .thenReturn(list);

        mvc.perform(get("/my-bucket")
                .accept(MediaType.APPLICATION_XML))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andExpect(xpath("/ListBucketResult/Contents/Key").string("readme.txt"));
    }

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void listObjects_withDelimiter_collapsesFolders() throws Exception {
        DriveItem doc = new DriveItem();
        doc.setId("dir-docs");
        doc.setName("docs");
        doc.setFolder(new Object());

        DriveItemList list = new DriveItemList();
        list.setItems(List.of(fileItem("a.txt", 1L, "etag1"), doc));

        when(driveService.listBucketChildren(eq("my-bucket"), any()))
            .thenReturn(list);

        mvc.perform(get("/my-bucket").param("delimiter", "/"))
            .andExpect(status().isOk())
            .andExpect(xpath("/ListBucketResult/Contents/Key").string("a.txt"))
            .andExpect(xpath("/ListBucketResult/CommonPrefixes/Prefix").string("docs/"));
    }

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void listObjects_v2_reportsKeyCount() throws Exception {
        DriveItemList list = new DriveItemList();
        list.setItems(List.of(fileItem("a.txt", 1L, "etag1")));

        when(driveService.listBucketChildren(eq("my-bucket"), any()))
            .thenReturn(list);

        mvc.perform(get("/my-bucket").param("list-type", "2"))
            .andExpect(status().isOk())
            .andExpect(xpath("/ListBucketResult/KeyCount").string("1"));
    }

    // ── GetObject ─────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void getObject_streamsContent() throws Exception {
        DriveItem meta = fileItem("hello.txt", 5L, "etag-hello");

        when(driveService.getItemMetadata("my-bucket", "hello.txt")).thenReturn(meta);
        when(driveService.downloadItem(eq("my-bucket"), eq("hello.txt"), isNull()))
            .thenReturn(bodyEntity("hello"));

        mvc.perform(get("/my-bucket/hello.txt"))
            .andExpect(status().isOk())
            .andExpect(content().bytes("hello".getBytes()));
    }

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void getObject_withRange_returns206() throws Exception {
        DriveItem meta = fileItem("big.bin", 100L, "etag-big");

        when(driveService.getItemMetadata("my-bucket", "big.bin")).thenReturn(meta);
        ResponseEntity<Flux<DataBuffer>> partial = ResponseEntity
            .status(206)
            .header("Content-Range", "bytes 0-9/100")
            .body(Flux.just(bufferFactory.wrap("0123456789".getBytes())));
        when(driveService.downloadItem("my-bucket", "big.bin", "bytes=0-9")).thenReturn(partial);

        mvc.perform(get("/my-bucket/big.bin").header("Range", "bytes=0-9"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes 0-9/100"))
            .andExpect(content().bytes("0123456789".getBytes()));
    }

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void getObject_ifNoneMatch_returns304() throws Exception {
        DriveItem meta = fileItem("cached.txt", 5L, "\"abc123\"");

        when(driveService.getItemMetadata("my-bucket", "cached.txt")).thenReturn(meta);

        mvc.perform(get("/my-bucket/cached.txt").header("If-None-Match", "\"abc123\""))
            .andExpect(status().isNotModified());
    }

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void getObject_ifMatchMismatch_returns412() throws Exception {
        DriveItem meta = fileItem("moved.txt", 5L, "\"aaa\"");

        when(driveService.getItemMetadata("my-bucket", "moved.txt")).thenReturn(meta);

        mvc.perform(get("/my-bucket/moved.txt").header("If-Match", "\"bbb\""))
            .andExpect(status().isPreconditionFailed());
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
        DriveItem result = fileItem("file.txt", 5L, "\"abc123\"");
        when(driveService.uploadSmall(eq("my-bucket"), eq("file.txt"), any(), any()))
            .thenReturn(result);

        mvc.perform(put("/my-bucket/file.txt")
                .contentType(MediaType.TEXT_PLAIN)
                .content("hello"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"abc123\""));

        verify(driveService).uploadSmall(eq("my-bucket"), eq("file.txt"), any(), any());
    }

    // ── CopyObject ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void putObject_withCopySource_copiesObject() throws Exception {
        DriveItem source = fileItem("orig.txt", 5L, "\"src-etag\"");
        when(driveService.getItemMetadata("src-bucket", "orig.txt")).thenReturn(source);
        when(driveService.downloadItem(eq("src-bucket"), eq("orig.txt"), isNull()))
            .thenReturn(bodyEntity("hello"));

        DriveItem copied = fileItem("copy.txt", 5L, "\"copy-etag\"");
        when(driveService.uploadSmall(eq("dst-bucket"), eq("copy.txt"), any(), any()))
            .thenReturn(copied);

        mvc.perform(put("/dst-bucket/copy.txt")
                .header("x-amz-copy-source", "src-bucket/orig.txt")
                .contentType(MediaType.APPLICATION_OCTET_STREAM))
            .andExpect(status().isOk())
            .andExpect(xpath("/CopyObjectResult/ETag").string("\"copy-etag\""));

        verify(driveService).uploadSmall(eq("dst-bucket"), eq("copy.txt"), any(), any());
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
        when(driveService.getItemMetadata("my-bucket", "file.txt"))
            .thenReturn(fileItem("file.txt", 100L, "etag1"));

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
