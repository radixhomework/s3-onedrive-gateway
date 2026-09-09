package io.github.radixhomework.s3onedrive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.radixhomework.s3onedrive.exception.S3Exception;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItem;
import io.github.radixhomework.s3onedrive.service.OneDriveService.DriveItemList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces S3 ListObjects results from the hierarchical OneDrive folder tree.
 *
 * A depth-first walk over the folder structure emits keys in a deterministic
 * order, collapsing folders into CommonPrefixes when a delimiter is given.
 * Listing is resumable: when a page fills the max-keys quota, the pending
 * folder stack is serialized into an opaque continuation token.
 */
@Slf4j
@Service
public class ListObjectsService {

    private static final ObjectMapper TOKEN_MAPPER = new ObjectMapper();

    private final OneDriveService driveService;

    public ListObjectsService(OneDriveService driveService) {
        this.driveService = driveService;
    }

    // ── API ───────────────────────────────────────────────────────────────────

    public record Request(String prefix, String delimiter, int maxKeys,
                          String continuationToken, String startAfter) {}

    public record Entry(String key, String lastModified, String etag, long size) {}

    public record Result(List<Entry> objects, List<String> commonPrefixes,
                         boolean truncated, String nextContinuationToken) {}

    // ── Listing ───────────────────────────────────────────────────────────────

    public Result list(String bucket, Request request) {
        String prefix = orEmpty(request.prefix());
        String delimiter = orEmpty(request.delimiter());
        int quota = request.maxKeys() <= 0 ? 1000 : Math.min(request.maxKeys(), 1000);

        Deque<Frame> stack;
        if (request.continuationToken() != null && !request.continuationToken().isBlank()) {
            stack = restore(request.continuationToken(), bucket);
        } else {
            stack = new ArrayDeque<>();
            stack.push(new Frame(""));
        }

        List<Entry> objects = new ArrayList<>();
        Set<String> prefixes = new LinkedHashSet<>();
        boolean truncated = false;
        String token = null;

        outer:
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.children == null) {
                DriveItemList listing = driveService.listBucketChildren(bucket, frame.path);
                List<DriveItem> items = listing != null && listing.getItems() != null
                    ? new ArrayList<>(listing.getItems()) : new ArrayList<>();
                items.sort(Comparator.comparing(DriveItem::getName));
                frame.children = items;
            }
            if (frame.idx >= frame.children.size()) {
                stack.pop();
                continue;
            }

            DriveItem item = frame.children.get(frame.idx);
            frame.idx++;
            String name = item.getName();
            String key = frame.path.isEmpty() ? name : frame.path + "/" + name;
            boolean isFolder = item.getFolder() != null;

            if (isFolder) {
                // Branch entirely before startAfter → skip it wholesale
                if (request.continuationToken() == null && request.startAfter() != null
                    && request.startAfter().compareTo(key) > 0
                    && !request.startAfter().startsWith(key + "/")) {
                    continue;
                }
                // Descend only if this folder can contain prefix matches
                if (!prefix.isEmpty() && !prefix.startsWith(key) && !key.startsWith(prefix)) {
                    continue;
                }
                // A OneDrive folder implies keys beneath it (S3 has no empty
                // folders), so roll it up into a common prefix without
                // descending whenever the delimiter cuts inside it.
                String commonPrefix = commonPrefixOf(key + "/", prefix, delimiter);
                if (commonPrefix != null) {
                    if (prefixes.add(commonPrefix)) {
                        if (objects.size() + prefixes.size() >= quota) {
                            truncated = true;
                            token = snapshot(stack);
                            break outer;
                        }
                    }
                } else {
                    stack.push(new Frame(key));
                }
            } else {
                if (!prefix.isEmpty() && !key.startsWith(prefix)) continue;
                if (request.continuationToken() == null && request.startAfter() != null
                    && key.compareTo(request.startAfter()) <= 0) {
                    continue;
                }
                String commonPrefix = commonPrefixOf(key, prefix, delimiter);
                if (commonPrefix != null) {
                    if (prefixes.add(commonPrefix)) {
                        if (objects.size() + prefixes.size() >= quota) {
                            truncated = true;
                            token = snapshot(stack);
                            break outer;
                        }
                    }
                } else {
                    objects.add(new Entry(key, item.getLastModifiedDateTime(),
                        quotedEtag(item), item.getSize() != null ? item.getSize() : 0L));
                    if (objects.size() + prefixes.size() >= quota) {
                        truncated = true;
                        token = snapshot(stack);
                        break outer;
                    }
                }
            }
        }

        return new Result(objects, new ArrayList<>(prefixes), truncated, token);
    }

    private static String commonPrefixOf(String key, String prefix, String delimiter) {
        if (delimiter.isEmpty()) return null;
        int pos = key.indexOf(delimiter, prefix.length());
        return pos < 0 ? null : key.substring(0, pos + delimiter.length());
    }

    private static String quotedEtag(DriveItem item) {
        String etag = item.getETag() != null ? item.getETag() : item.getId();
        if (etag == null) return null;
        return etag.startsWith("\"") ? etag : "\"" + etag + "\"";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ── Continuation tokens ───────────────────────────────────────────────────

    private record FrameState(String p, int i) {}

    private String snapshot(Deque<Frame> stack) {
        try {
            List<FrameState> state = new ArrayList<>();
            for (Iterator<Frame> it = stack.descendingIterator(); it.hasNext(); ) {
                Frame f = it.next();
                state.add(new FrameState(f.path, f.idx));
            }
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(TOKEN_MAPPER.writeValueAsBytes(state));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode continuation token", e);
        }
    }

    private Deque<Frame> restore(String token, String bucket) {
        List<FrameState> state;
        try {
            state = List.of(TOKEN_MAPPER.readValue(
                Base64.getUrlDecoder().decode(token), FrameState[].class));
        } catch (Exception e) {
            throw new S3Exception(400, "InvalidArgument", "Invalid continuation token");
        }
        Deque<Frame> stack = new ArrayDeque<>();
        for (FrameState s : state) {
            Frame frame = new Frame(s.p());
            frame.idx = s.i();
            stack.push(frame);
        }
        return stack;
    }

    /** A pending directory in the depth-first walk. */
    private static final class Frame {
        final String path;
        List<DriveItem> children;
        int idx;

        Frame(String path) {
            this.path = path;
        }
    }
}
