package tantalum.shell.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.attachment.AttachmentRejected.Reason;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;

/** 任务 5：目录布局、去重、配额、按用户隔离、临时文件、完整性。 */
class AttachmentStoreTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @TempDir
    Path data;

    private AttachmentStore store(AttachmentLimits limits) throws IOException {
        return new AttachmentStore(data, limits, new ImageNormalizer(limits));
    }

    private AttachmentStore store() throws IOException {
        return store(AttachmentLimits.defaults());
    }

    /** 图片和文件用同一个单个上限。 */
    private static AttachmentLimits limits(long maxBytes, long quotaBytes) {
        return new AttachmentLimits(20, 1L << 30, maxBytes, 64_000_000, 65_535, 20, maxBytes, quotaBytes, 2);
    }

    private static AttachmentRef upload(AttachmentStore store, Principal who, String name, byte[] bytes)
            throws IOException, AttachmentRejected {
        return store.upload(who, name, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sample(String name) throws IOException {
        try (InputStream in = AttachmentStoreTest.class.getResourceAsStream("/attachments/" + name)) {
            return in.readAllBytes();
        }
    }

    private Path objects(Principal who) {
        return data.resolve("attachments").resolve(SessionPaths.dirName(who)).resolve("objects");
    }

    private static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.sorted().toList();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void sameContentIsStoredOnceUnderItsHashAndKeepsEachName() throws Exception {
        AttachmentStore store = store();
        AttachmentRef first = upload(store, ALICE, "a.txt", text("hello"));
        AttachmentRef second = upload(store, ALICE, "dir/b.txt", text("hello"));
        assertEquals(sha256(text("hello")), first.id());
        assertEquals(first.id(), second.id());
        assertEquals("a.txt", first.name());
        assertEquals("b.txt", second.name());
        assertEquals(new FileRef(first.id(), "a.txt", "text/plain", 5, null), first);
        assertEquals(List.of(objects(ALICE).resolve(first.hash()), objects(ALICE).resolve(first.hash() + ".json")),
                list(objects(ALICE)));
        assertEquals(5, store.used(ALICE));
    }

    @Test
    void imagesAreStoredNormalizedAndHashedAfterNormalizing() throws Exception {
        AttachmentStore store = store();
        AttachmentRef ref = upload(store, ALICE, "IMG_0001.JPG", sample("rotated.jpg"));
        ImageRef image = assertInstanceOf(ImageRef.class, ref);
        assertEquals("image/jpeg", image.mediaType());
        assertEquals(8, image.width());
        assertEquals(16, image.height());
        byte[] stored = Files.readAllBytes(objects(ALICE).resolve(image.hash()));
        assertEquals(sha256(stored), image.id());
        assertEquals(stored.length, image.bytes());
        assertEquals(image.id(), upload(store, ALICE, "again.jpg", sample("rotated.jpg")).id());
        assertEquals(Optional.of(new ImageRef(image.id(), "shown.jpg", "image/jpeg", image.bytes(), 8, 16)),
                store.ref(ALICE, image.id(), "shown.jpg"));
    }

    @Test
    void namesThatClaimAnImageMustBeImages() throws Exception {
        AttachmentStore store = store();
        AttachmentRejected rejected = assertThrows(AttachmentRejected.class, () -> upload(store, ALICE, "cat.png", text("not a cat")));
        assertEquals(Reason.INVALID_IMAGE, rejected.reason());
        assertEquals(List.of(), list(objects(ALICE)));
        assertEquals(0, store.used(ALICE));
    }

    @Test
    void stopsReadingAtTheSizeLimit() throws Exception {
        AttachmentStore store = store(limits(10, 1000));
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 'x';
            }
        };
        AttachmentRejected rejected = assertThrows(AttachmentRejected.class,
                () -> store.upload(ALICE, "big.txt", "text/plain", endless, -1));
        assertEquals(Reason.TOO_LARGE, rejected.reason());
        assertEquals(java.util.Map.of("limit", 10L), rejected.params(), "上限的字节数，不预先格式化");
        assertEquals(List.of(), list(objects(ALICE)));
        assertEquals(List.of(objects(ALICE)), list(objects(ALICE).getParent()));
    }

    @Test
    void quotaCountsStoredBytesAndSurvivesARestart() throws Exception {
        AttachmentStore store = store(limits(100, 10));
        upload(store, ALICE, "a.txt", text("123456"));
        AttachmentRejected full = assertThrows(AttachmentRejected.class, () -> upload(store, ALICE, "b.txt", text("abcdef")));
        assertEquals(Reason.QUOTA_FULL, full.reason());
        assertEquals("the attachment quota is full", full.getMessage());
        // 没报长度时按上限算，所以也会先拒绝；别人的配额各算各的。
        assertEquals(Reason.QUOTA_FULL, assertThrows(AttachmentRejected.class,
                () -> store.upload(ALICE, "c.txt", "text/plain", new ByteArrayInputStream(text("1")), -1)).reason());
        upload(store, BOB, "b.txt", text("abcdef"));

        AttachmentStore restarted = store(limits(100, 10));
        assertEquals(6, restarted.used(ALICE));
        assertEquals(Reason.QUOTA_FULL,
                assertThrows(AttachmentRejected.class, () -> upload(restarted, ALICE, "b.txt", text("abcdef"))).reason());
    }

    /** 两个用户上传同样的内容各存一份；别人的 ID 在自己这里不存在（A1）。 */
    @Test
    void otherUsersAttachmentsDoNotExist() throws Exception {
        AttachmentStore store = store();
        AttachmentRef alices = upload(store, ALICE, "a.txt", text("secret"));
        assertTrue(store.find(ALICE, alices.id()).isPresent());
        assertEquals(Optional.empty(), store.find(BOB, alices.id()));
        assertEquals(Optional.empty(), store.ref(BOB, alices.id(), "a.txt"));
        assertThrows(NoSuchFileException.class, () -> store.bytes(BOB, alices.id()));
        assertEquals(Optional.empty(), store.find(ALICE, "sha256:../../bob/objects/" + alices.hash()));
        assertEquals(Optional.empty(), store.find(ALICE, alices.hash()));
    }

    @Test
    void concurrentUploadsOfTheSameContentStoreOneObject() throws Exception {
        AttachmentStore store = store();
        byte[] content = text("x".repeat(100_000));
        int uploads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AttachmentRef>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(uploads)) {
            for (int i = 0; i < uploads; i++) {
                String name = "copy-" + i + ".txt";
                results.add(pool.submit(() -> {
                    start.await();
                    return upload(store, ALICE, name, content);
                }));
            }
            start.countDown();
            for (Future<AttachmentRef> result : results) {
                assertEquals(sha256(content), result.get().id());
            }
        }
        assertEquals(2, list(objects(ALICE)).size());
        assertEquals(content.length, store.used(ALICE));
        assertEquals(content.length, store(AttachmentLimits.defaults()).used(ALICE));
    }

    @Test
    void unfinishedUploadsAreRemovedAtStartup() throws Exception {
        AttachmentStore store = store();
        AttachmentRef kept = upload(store, ALICE, "a.txt", text("kept"));
        Path left = Files.writeString(objects(ALICE).getParent().resolve("upload-123.part"), "half");
        store();
        assertFalse(Files.exists(left));
        assertTrue(Files.exists(objects(ALICE).resolve(kept.hash())));
    }

    /** 第一次用到时核对哈希。改坏的对象在新进程里第一次读就报错。 */
    @Test
    void corruptedObjectsAreDetectedOnFirstUse() throws Exception {
        AttachmentStore store = store();
        AttachmentRef ref = upload(store, ALICE, "a.txt", text("original"));
        assertArrayEquals(text("original"), store.bytes(ALICE, ref.id()));
        Files.writeString(objects(ALICE).resolve(ref.hash()), "tampered");
        IOException mismatch = assertThrows(IOException.class, () -> store().bytes(ALICE, ref.id()));
        assertTrue(mismatch.getMessage().contains(ref.id()), mismatch.getMessage());
        Files.delete(objects(ALICE).resolve(ref.hash()));
        assertThrows(NoSuchFileException.class, () -> store.bytes(ALICE, ref.id()));
    }

    @Test
    void pdfsAreRecognizedByTheirHeaderAndPagesAreCounted() throws Exception {
        AttachmentStore store = store();
        FileRef pdf = assertInstanceOf(FileRef.class, store.upload(ALICE, "report", "application/octet-stream",
                new ByteArrayInputStream(sample("two-pages.pdf")), -1));
        assertEquals(2, pdf.pages());
        assertEquals("application/pdf", pdf.mediaType());
        FileRef fake = assertInstanceOf(FileRef.class, store.upload(ALICE, "fake.pdf", "application/pdf",
                new ByteArrayInputStream(text("%PDF-1.7 broken")), -1));
        assertNull(fake.pages());
        assertEquals("application/pdf", fake.mediaType());
        FileRef odd = assertInstanceOf(FileRef.class, store.upload(ALICE, "x.bin", "not a type\r\n",
                new ByteArrayInputStream(text("x")), -1));
        assertEquals("application/octet-stream", odd.mediaType());
    }

    @Test
    void directoriesAndFilesArePrivate() throws Exception {
        AttachmentRef ref = upload(store(), ALICE, "a.txt", text("private"));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(objects(ALICE))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(objects(ALICE).getParent())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(objects(ALICE).resolve(ref.hash()))));
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(objects(ALICE).resolve(ref.hash() + ".json"))));
    }
}
