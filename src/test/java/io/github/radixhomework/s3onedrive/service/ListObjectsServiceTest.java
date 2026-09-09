package io.github.radixhomework.s3onedrive.service;

import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItem;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItemList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class ListObjectsServiceTest {

    private OneDriveService driveService;
    private ListObjectsService service;

    @BeforeEach
    void setUp() {
        driveService = Mockito.mock(OneDriveService.class);
        service = new ListObjectsService(driveService);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static DriveItem file(String name) {
        DriveItem item = new DriveItem();
        item.setId("id-" + name);
        item.setName(name);
        item.setSize(42L);
        item.setLastModifiedDateTime("2024-01-01T00:00:00Z");
        item.setETag("etag" + name);
        item.setFile(new Object());
        return item;
    }

    private static DriveItem folder(String name) {
        DriveItem item = new DriveItem();
        item.setId("dir-" + name);
        item.setName(name);
        item.setFolder(new Object());
        return item;
    }

    private static DriveItemList listing(DriveItem... items) {
        DriveItemList list = new DriveItemList();
        list.setItems(List.of(items));
        return list;
    }

    private void stubChildren(String bucket, String folder, DriveItemList listing) {
        when(driveService.listBucketChildren(eq(bucket), eq(folder))).thenReturn(listing);
    }

    private static List<String> keys(ListObjectsService.Result result) {
        return result.objects().stream().map(ListObjectsService.Entry::key).toList();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void flatListing_returnsSortedKeys() {
        stubChildren("bkt", "", listing(file("b.txt"), file("a.txt")));

        ListObjectsService.Result result = service.list("bkt",
            new ListObjectsService.Request("", "", 1000, null, null));

        assertThat(keys(result)).containsExactly("a.txt", "b.txt");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void nestedKeys_areWalkedRecursively() {
        stubChildren("bkt", "", listing(folder("docs"), file("a.txt")));
        stubChildren("bkt", "docs", listing(file("readme.md")));

        ListObjectsService.Result result = service.list("bkt",
            new ListObjectsService.Request("", "", 1000, null, null));

        assertThat(keys(result)).containsExactly("a.txt", "docs/readme.md");
    }

    @Test
    void delimiter_collapsesFoldersIntoCommonPrefixes() {
        stubChildren("bkt", "", listing(folder("docs"), file("a.txt")));

        ListObjectsService.Result result = service.list("bkt",
            new ListObjectsService.Request("", "/", 1000, null, null));

        assertThat(keys(result)).containsExactly("a.txt");
        assertThat(result.commonPrefixes()).containsExactly("docs/");
    }

    @Test
    void prefixRestrictsListing() {
        stubChildren("bkt", "", listing(folder("docs"), file("a.txt")));
        stubChildren("bkt", "docs", listing(file("readme.md"), folder("sub")));
        stubChildren("bkt", "docs/sub", listing(file("deep.txt")));

        ListObjectsService.Result result = service.list("bkt",
            new ListObjectsService.Request("docs/", "/", 1000, null, null));

        assertThat(keys(result)).containsExactly("docs/readme.md");
        assertThat(result.commonPrefixes()).containsExactly("docs/sub/");
    }

    @Test
    void maxKeys_truncatesAndContinuationTokenResumes() {
        stubChildren("bkt", "", listing(file("a.txt"), file("b.txt"), file("c.txt")));

        ListObjectsService.Request request =
            new ListObjectsService.Request("", "", 2, null, null);
        ListObjectsService.Result page1 = service.list("bkt", request);

        assertThat(keys(page1)).containsExactly("a.txt", "b.txt");
        assertThat(page1.truncated()).isTrue();
        assertThat(page1.nextContinuationToken()).isNotBlank();

        ListObjectsService.Result page2 = service.list("bkt",
            new ListObjectsService.Request("", "", 2, page1.nextContinuationToken(), null));

        assertThat(keys(page2)).containsExactly("c.txt");
        assertThat(page2.truncated()).isFalse();
    }

    @Test
    void startAfter_skipsEarlierKeys() {
        stubChildren("bkt", "", listing(file("a.txt"), file("b.txt"), file("c.txt")));

        ListObjectsService.Result result = service.list("bkt",
            new ListObjectsService.Request("", "", 1000, null, "b.txt"));

        assertThat(keys(result)).containsExactly("c.txt");
    }

    @Test
    void continuation_acrossFolderBoundary() {
        stubChildren("bkt", "", listing(file("a.txt"), folder("docs")));
        stubChildren("bkt", "docs", listing(file("one.txt"), file("two.txt"), file("three.txt")));

        ListObjectsService.Result page1 = service.list("bkt",
            new ListObjectsService.Request("", "", 2, null, null));

        assertThat(keys(page1)).containsExactly("a.txt", "docs/one.txt");
        assertThat(page1.truncated()).isTrue();

        ListObjectsService.Result page2 = service.list("bkt",
            new ListObjectsService.Request("", "", 2, page1.nextContinuationToken(), null));

        // lexicographic order inside "docs": one < three < two
        assertThat(keys(page2)).containsExactly("docs/three.txt", "docs/two.txt");
    }

    @Test
    void graphPaginationIsNullSafe() {
        stubChildren("bkt", "", null);

        ListObjectsService.Result result = service.list("bkt",
            new ListObjectsService.Request("", "", 1000, null, null));

        assertThat(result.objects()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }
}
