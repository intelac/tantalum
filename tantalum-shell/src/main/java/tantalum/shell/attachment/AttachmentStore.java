package tantalum.shell.attachment;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.attachment.AttachmentRejected.Reason;
import tantalum.shell.attachment.ImageNormalizer.Normalized;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tools.jackson.databind.json.JsonMapper;

/**
 * 每个用户自己的附件目录（设计第 2 节，需求 A1、A5、2.7、4.2）：
 * <pre>
 * attachments/&lt;用户&gt;/
 *   objects/&lt;64 位哈希&gt;        存储的字节（图片是规范化后的，文件原样）
 *   objects/&lt;64 位哈希&gt;.json   元数据
 *   upload-&lt;随机&gt;.part         上传中的临时文件，启动时清掉
 * </pre>
 * 同一用户同样的内容只存一份。所有读取都只在当前用户的目录里找，别人的和不存在的一样。
 */
public final class AttachmentStore {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentStore.class);
    private static final String OBJECTS = "objects";
    private static final String PART = ".part";
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final HexFormat HEX = HexFormat.of();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 一个附件对象：存储的文件和它的元数据。 */
    public record Stored(Path file, Meta meta) {}

    /** 元数据文件的内容。和名字无关：名字属于每一次出现，在引用里。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(String kind, String mediaType, long bytes, Integer width, Integer height, Integer sourceWidth,
                       Integer sourceHeight, Integer pages) {

        boolean image() {
            return "image".equals(kind);
        }
    }

    private final Path root;
    private final AttachmentLimits limits;
    private final ImageNormalizer normalizer;
    /** 每个用户已存储的字节数，第一次用到时扫目录得到。值对象同时是这个用户提交新对象时的锁。 */
    private final ConcurrentHashMap<String, Usage> usage = new ConcurrentHashMap<>();
    /** 这个进程里已核对过哈希的对象（用户目录/哈希）。 */
    private final Set<String> verified = ConcurrentHashMap.newKeySet();

    private static final class Usage {
        long bytes;
    }

    public AttachmentStore(Path dataDir, AttachmentLimits limits, ImageNormalizer normalizer) throws IOException {
        this.root = dataDir.resolve("attachments").toAbsolutePath().normalize();
        this.limits = limits;
        this.normalizer = normalizer;
        removeUnfinishedUploads();
    }

    /**
     * 读完请求体、规范化、存好，返回带清洗后名字的引用。{@code declaredLength} 是 {@code Content-Length}，不知道时传 -1。
     * {@code mediaType} 是浏览器报的类型，只用于文件的显示。
     */
    public AttachmentRef upload(Principal principal, String rawName, String mediaType, InputStream body, long declaredLength)
            throws IOException, AttachmentRejected {
        Path userDir = userDir(principal);
        long expected = declaredLength >= 0 ? declaredLength : limits.maxUploadBytes();
        if (used(principal, userDir) + Math.min(expected, limits.maxUploadBytes()) > limits.quotaBytes()) {
            throw quotaFull();
        }
        createPrivateDir(userDir.resolve(OBJECTS));
        Path part = userDir.resolve("upload-" + UUID.randomUUID() + PART);
        try {
            String hash = receive(body, part);
            Optional<String> imageType = ImageNormalizer.sniff(part);
            String name;
            Meta meta;
            if (imageType.isPresent()) {
                name = FileNames.clean(rawName, true);
                if (Files.size(part) > limits.maxImageBytes()) {
                    throw tooLarge(Reason.IMAGE_FILE_TOO_LARGE, "image", limits.maxImageBytes());
                }
                Normalized image = normalizer.normalize(part);
                Files.write(part, image.bytes());
                hash = sha256(image.bytes());
                meta = new Meta("image", image.mediaType(), image.bytes().length, image.width(), image.height(),
                        image.sourceWidth(), image.sourceHeight(), null);
            } else {
                name = FileNames.clean(rawName, false);
                if (rawName != null && ImageNormalizer.claimsImage(name)) {
                    throw new AttachmentRejected(Reason.INVALID_IMAGE, "the file name says image, but the content is not a PNG, JPEG, WebP or GIF image");
                }
                long size = Files.size(part);
                if (size > limits.maxFileBytes()) {
                    throw tooLarge(Reason.TOO_LARGE, "file", limits.maxFileBytes());
                }
                Integer pages = pdfPages(part);
                String type = pages != null ? "application/pdf" : displayType(mediaType);
                meta = new Meta("file", type, size, null, null, null, null, pages);
            }
            commit(principal, userDir, hash, part, meta);
            return ref("sha256:" + hash, name, meta);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** 本人目录里的附件。ID 格式不对、不存在、不是本人的都返回空。 */
    public Optional<Stored> find(Principal principal, String id) throws IOException {
        if (!AttachmentRef.isId(id)) {
            return Optional.empty();
        }
        Path objects = userDir(principal).resolve(OBJECTS);
        String hash = id.substring(AttachmentRef.PREFIX.length());
        Path file = objects.resolve(hash);
        Path metaFile = objects.resolve(hash + ".json");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(metaFile)) {
            return Optional.empty();
        }
        return Optional.of(new Stored(file, JSON.readValue(metaFile.toFile(), Meta.class)));
    }

    /** 按本人目录里的元数据组成引用；提交时用它，客户端报的尺寸和大小一概不用（设计第 2 节）。 */
    public Optional<AttachmentRef> ref(Principal principal, String id, String name) throws IOException {
        Optional<Stored> stored = find(principal, id);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Meta meta = stored.get().meta();
        return Optional.of(ref(id, FileNames.clean(name, meta.image()), meta));
    }

    /**
     * 发给模型前取字节。进程里第一次用到时核对哈希，之后不再核对（需求 4.2）。
     *
     * @throws NoSuchFileException 附件不在了
     * @throws IOException 哈希和 ID 不符
     */
    public byte[] bytes(Principal principal, String id) throws IOException {
        Stored stored = find(principal, id).orElseThrow(() -> new NoSuchFileException("attachment " + id + " is missing"));
        byte[] bytes = Files.readAllBytes(stored.file());
        String key = SessionPaths.dirName(principal) + "/" + id;
        if (!verified.contains(key)) {
            if (!("sha256:" + sha256(bytes)).equals(id)) {
                throw new IOException("attachment " + id + " does not match its hash");
            }
            verified.add(key);
        }
        return bytes;
    }

    /** 请求用图片版本的缓存（设计第 4 节）：{@code requests/<哈希>-<版本键>}，可以删，删了重新生成。 */
    Path requestCache(Principal principal, String hash, String versionKey) throws IOException {
        Path requests = userDir(principal).resolve("requests");
        createPrivateDir(requests);
        return requests.resolve(hash + "-" + versionKey);
    }

    /** 原子地写一个只有本人能读的文件：先写临时文件再改名，读的人不会看到半个。 */
    static void writePrivately(Path target, byte[] bytes) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + "-" + UUID.randomUUID() + PART);
        try {
            createPrivateFile(part);
            Files.write(part, bytes);
            force(part);
            PrivateFiles.replace(part, target);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** 附件的各项上限：工具结果里的图片也按“一条消息最多几张”收（web-tools W8）。 */
    public AttachmentLimits limits() {
        return limits;
    }

    /** 用户已存储的字节数。 */
    public long used(Principal principal) throws IOException {
        return used(principal, userDir(principal));
    }

    private long used(Principal principal, Path userDir) throws IOException {
        Usage counted = usage.get(SessionPaths.dirName(principal));
        if (counted != null) {
            synchronized (counted) {
                return counted.bytes;
            }
        }
        Usage scanned = new Usage();
        scanned.bytes = scan(userDir.resolve(OBJECTS));
        Usage existing = usage.putIfAbsent(SessionPaths.dirName(principal), scanned);
        Usage current = existing == null ? scanned : existing;
        synchronized (current) {
            return current.bytes;
        }
    }

    private static long scan(Path objects) throws IOException {
        if (!Files.isDirectory(objects)) {
            return 0;
        }
        long total = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(objects)) {
            for (Path entry : entries) {
                if (!entry.getFileName().toString().endsWith(".json")) {
                    total += Files.size(entry);
                }
            }
        }
        return total;
    }

    /** 已有同样内容就只删临时文件；否则先落对象再落元数据，元数据在才算存好。配额在这里再查一次，挡住并发上传。 */
    private void commit(Principal principal, Path userDir, String hash, Path part, Meta meta) throws IOException, AttachmentRejected {
        used(principal, userDir);
        Usage counted = usage.get(SessionPaths.dirName(principal));
        Path objects = userDir.resolve(OBJECTS);
        Path object = objects.resolve(hash);
        Path metaFile = objects.resolve(hash + ".json");
        synchronized (counted) {
            if (Files.isRegularFile(object) && Files.isRegularFile(metaFile)) {
                return;
            }
            boolean known = Files.isRegularFile(object);
            if (!known && counted.bytes + meta.bytes() > limits.quotaBytes()) {
                throw quotaFull();
            }
            force(part);
            PrivateFiles.replace(part, object);
            writePrivately(metaFile, JSON.writeValueAsBytes(meta));
            if (!known) {
                counted.bytes += meta.bytes();
            }
        }
    }

    /** 边读边写临时文件、边算哈希；超过单个上传的上限立即停止读取（设计第 2 节第 3 步）。 */
    private String receive(InputStream body, Path part) throws IOException, AttachmentRejected {
        createPrivateFile(part);
        MessageDigest digest = sha256();
        long limit = limits.maxUploadBytes();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(part, StandardOpenOption.TRUNCATE_EXISTING), digest)) {
            for (int read = body.read(buffer); read >= 0; read = body.read(buffer)) {
                total += read;
                if (total > limit) {
                    throw tooLarge(Reason.TOO_LARGE, "file", limit);
                }
                out.write(buffer, 0, read);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    /** 文件头是 {@code %PDF-} 且数得出页数才算 PDF；加密或损坏的当普通文件。 */
    private static Integer pdfPages(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(PDF.length);
            if (!Arrays.equals(head, PDF)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            int pages = document.getNumberOfPages();
            return pages > 0 ? pages : null;
        } catch (IOException | RuntimeException e) {
            LOG.debug("not counting pages of an unreadable PDF: {}", e.toString());
            return null;
        }
    }

    /** 浏览器报的类型只显示用；不像 {@code type/subtype} 的一律当未知。 */
    private static String displayType(String mediaType) {
        if (mediaType == null || mediaType.length() > 100 || !mediaType.matches("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*")) {
            return FileRef.UNKNOWN_TYPE;
        }
        return mediaType.toLowerCase(Locale.ROOT);
    }

    private static AttachmentRef ref(String id, String name, Meta meta) {
        if (meta.image()) {
            return new ImageRef(id, name, meta.mediaType(), meta.bytes(), meta.width(), meta.height(), meta.sourceWidth(),
                    meta.sourceHeight());
        }
        return new FileRef(id, name, meta.mediaType(), meta.bytes(), meta.pages());
    }

    private Path userDir(Principal principal) {
        Path dir = root.resolve(SessionPaths.dirName(principal)).normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            throw new IllegalArgumentException("path escapes the attachments directory: " + dir);
        }
        return dir;
    }

    /** 上一个进程留下的半个上传。 */
    private void removeUnfinishedUploads() throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> users = Files.list(root)) {
            for (Path user : users.filter(Files::isDirectory).toList()) {
                try (DirectoryStream<Path> parts = Files.newDirectoryStream(user, "*" + PART)) {
                    for (Path part : parts) {
                        Files.deleteIfExists(part);
                    }
                }
            }
        }
    }

    private static void createPrivateDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            PrivateFiles.createDirectories(dir);
        }
    }

    private static void createPrivateFile(Path file) throws IOException {
        PrivateFiles.createFile(file);
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        return HEX.formatHex(sha256().digest(bytes));
    }

    /** 上限的字节数作为参数，不预先格式化：控制台按看的人的语言写成“50 MB”。 */
    private static AttachmentRejected tooLarge(Reason reason, String what, long limit) {
        return new AttachmentRejected(reason, "the " + what + " is larger than the limit of " + limit + " bytes", Map.of("limit", limit));
    }

    private static AttachmentRejected quotaFull() {
        return new AttachmentRejected(Reason.QUOTA_FULL, "the attachment quota is full");
    }
}
