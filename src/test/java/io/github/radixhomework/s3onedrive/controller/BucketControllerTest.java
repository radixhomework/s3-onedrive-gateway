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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BucketControllerTest {

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

    // ── ListBuckets ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void listBuckets_returnsBucketsXml() throws Exception {
        DriveItem folder = new DriveItem();
        folder.setId("folder1");
        folder.setName("my-bucket");
        folder.setCreatedDateTime("2024-01-01T00:00:00Z");
        folder.setFolder(new Object());

        DriveItemList list = new DriveItemList();
        list.setItems(List.of(folder));

        when(driveService.listRootChildren()).thenReturn(list);

        mvc.perform(get("/").accept(MediaType.APPLICATION_XML))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andExpect(xpath("/ListAllMyBucketsResult/Buckets/Bucket/Name")
                .string("my-bucket"));
    }

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void listBuckets_emptyDrive_returnsEmptyList() throws Exception {
        DriveItemList empty = new DriveItemList();
        empty.setItems(List.of());
        when(driveService.listRootChildren()).thenReturn(empty);

        mvc.perform(get("/").accept(MediaType.APPLICATION_XML))
            .andExpect(status().isOk())
            .andExpect(xpath("count(/ListAllMyBucketsResult/Buckets/Bucket)").number(0.0));
    }

    // ── CreateBucket ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void createBucket_callsCreateFolder() throws Exception {
        doNothing().when(driveService).createFolder(any());

        mvc.perform(put("/new-bucket"))
            .andExpect(status().isOk())
            .andExpect(header().string("Location", "/new-bucket"));

        verify(driveService).createFolder("new-bucket");
    }

    // ── DeleteBucket ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void deleteBucket_existing_returns204() throws Exception {
        when(driveService.deleteItem("old-bucket", null)).thenReturn(true);

        mvc.perform(delete("/old-bucket"))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void deleteBucket_notFound_returns404() throws Exception {
        when(driveService.deleteItem("ghost-bucket", null)).thenReturn(false);

        mvc.perform(delete("/ghost-bucket"))
            .andExpect(status().isNotFound());
    }

    // ── HeadBucket ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void headBucket_existing_returns200() throws Exception {
        DriveItem meta = new DriveItem();
        meta.setId("id1");
        meta.setFolder(new Object());
        when(driveService.getItemMetadata("my-bucket", null)).thenReturn(meta);

        mvc.perform(head("/my-bucket"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "S3_CLIENT")
    void headBucket_missing_returns404() throws Exception {
        when(driveService.getItemMetadata("no-bucket", null)).thenReturn(null);

        mvc.perform(head("/no-bucket"))
            .andExpect(status().isNotFound());
    }
}
