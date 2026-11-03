package tantalum.shell.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.attachment.ImageNormalizer.Target;
import tantalum.shell.attachment.RequestImages.Version;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;

/** 任务 13：请求用的图片版本，满足目标时用存储的字节，否则确定性地再编码一次并缓存。 */
class RequestImagesTest {

    private static final Principal ALICE = new Principal("alice");

    @TempDir
    Path data;

    private AttachmentStore store;
    private RequestImages images;
    private ImageRef photo;

    @BeforeEach
    void setUp() throws Exception {
        store = new AttachmentStore(data, AttachmentLimits.defaults(), new ImageNormalizer(AttachmentLimits.defaults()));
        images = new RequestImages(store);
        try (InputStream in = RequestImagesTest.class.getResourceAsStream("/attachments/rotated.jpg")) {
            byte[] bytes = in.readAllBytes();
            photo = assertInstanceOf(ImageRef.class, store.upload(ALICE, "photo.jpg", null, new ByteArrayInputStream(bytes), bytes.length));
        }
    }

    private Path requests() {
        return data.resolve("attachments").resolve(SessionPaths.dirName(ALICE)).resolve("requests");
    }

    @Test
    void storedBytesAreUsedWhenTheyAlreadyFit() throws Exception {
        Version version = images.of(ALICE, photo, RequestImages.MODEL);
        assertArrayEquals(store.bytes(ALICE, photo.id()), version.bytes());
        assertEquals(8, version.width());
        assertEquals(0, images.encodes());
        assertTrue(!Files.exists(requests()) || Files.list(requests()).findAny().isEmpty());
    }

    /** 8×16 的图片对 4 像素的目标太大：缩到 2×4，存进缓存；第二次直接读缓存，不再编码，字节相同。 */
    @Test
    void oversizedImagesAreShrunkOnceAndCached() throws Exception {
        Target small = new Target(4, 100_000);
        Version first = images.of(ALICE, photo, small);
        assertEquals(2, first.width());
        assertEquals(4, first.height());
        assertEquals("image/jpeg", first.mediaType());
        assertEquals(1, images.encodes());
        Version second = images.of(ALICE, photo, small);
        assertArrayEquals(first.bytes(), second.bytes());
        assertEquals(2, second.width());
        assertEquals(4, second.height());
        assertEquals(1, images.encodes());
        try (Stream<Path> cached = Files.list(requests())) {
            assertEquals(photo.hash() + "-4x100000-v1", cached.findFirst().orElseThrow().getFileName().toString());
        }
    }

    @Test
    void byteLimitReencodesUntilItFits() throws Exception {
        Target tight = new Target(2048, photo.bytes() - 1);
        Version version = images.of(ALICE, photo, tight);
        assertTrue(version.bytes().length < photo.bytes(), version.bytes().length + " bytes");
        assertEquals(1, images.encodes());
    }

    /** 1×1 的 JPEG 也有几百字节：放不下时报错，不会一直缩下去。 */
    @Test
    void anImpossibleTargetFailsInsteadOfLooping() {
        assertThrows(IOException.class, () -> images.of(ALICE, photo, new Target(2048, 100)));
    }

    @Test
    void aDeletedCacheIsRegeneratedWithTheSameBytes() throws Exception {
        Target small = new Target(4, 100_000);
        byte[] first = images.of(ALICE, photo, small).bytes();
        try (Stream<Path> cached = Files.list(requests())) {
            for (Path file : cached.toList()) {
                Files.delete(file);
            }
        }
        assertArrayEquals(first, images.of(ALICE, photo, small).bytes());
        assertEquals(2, images.encodes());
    }

    @Test
    void aMissingAttachmentFailsWithItsId() throws Exception {
        Files.delete(data.resolve("attachments").resolve(SessionPaths.dirName(ALICE)).resolve("objects").resolve(photo.hash()));
        NoSuchFileException missing = assertThrows(NoSuchFileException.class, () -> images.of(ALICE, photo, RequestImages.MODEL));
        assertTrue(missing.getMessage().contains(photo.id()), missing.getMessage());
    }
}
