package tantalum.shell.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.JsonText;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/** 一个会话的只追加文件。第一行是头部，之后每行一个事件。{@link #append} 返回才算落盘。调用方保证同一文件同时只有一个写入者。 */
public final class SessionFile implements AutoCloseable {

    static final int FORMAT = 1;

    private static final String DIR_MODE = "rwx------";
    private static final String FILE_MODE = "rw-------";

    private final Principal principal;
    private final SessionId session;
    private final EventCodec codec;
    private final FileChannel channel;
    /** 新建时新出现的目录项所在的目录：第一批事件落盘后再 fsync，省掉单独为头部做的一次 fsync。 */
    private List<Path> unsyncedDirs;

    private SessionFile(Principal principal, SessionId session, EventCodec codec, FileChannel channel) {
        this.principal = principal;
        this.session = session;
        this.codec = codec;
        this.channel = channel;
    }

    /**
     * 打开写入，先截掉上次崩溃留下的残缺尾部。文件不存在时才创建，所以只有第一次写入才产生文件。
     * 新文件的头部和第一批事件一起 fsync：在那之前这批事件还没交给内核，崩溃只留下残缺的头部，下次打开时截掉重写。
     */
    public static SessionFile open(SessionPaths paths, Principal principal, SessionId session, EventCodec codec) throws IOException {
        Path file = paths.sessionFile(principal, session);
        SessionFileReader.repairTail(file);
        List<Path> unsynced = List.of();
        if (!Files.exists(file) || Files.size(file) == 0) {
            unsynced = create(paths, file, principal, session);
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(FILE_MODE));
        SessionFile opened = new SessionFile(principal, session, codec, FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND));
        opened.unsyncedDirs = unsynced;
        return opened;
    }

    /** 写头部，不 fsync。返回第一批事件落盘后要 fsync 的目录。 */
    private static List<Path> create(SessionPaths paths, Path file, Principal principal, SessionId session) throws IOException {
        boolean rootIsNew = createPrivateDir(paths.sessionsDir());
        boolean userIsNew = createPrivateDir(file.getParent());
        boolean fileIsNew = !Files.exists(file);
        if (fileIsNew) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_MODE)));
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            writeFully(channel, ByteBuffer.wrap(header(principal, session)));
        }
        // 文件内容落盘还不够，目录项也要落盘，否则崩溃后文件可能不见。
        List<Path> dirs = new java.util.ArrayList<>();
        if (fileIsNew) {
            dirs.add(file.getParent());
        }
        if (userIsNew) {
            dirs.add(paths.sessionsDir());
        }
        if (rootIsNew) {
            dirs.add(paths.sessionsDir().getParent());
        }
        return dirs;
    }

    private static boolean createPrivateDir(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return false;
        }
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(DIR_MODE)));
        return true;
    }

    private static void syncDir(Path dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static byte[] header(Principal principal, SessionId session) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("format", FORMAT);
        fields.put("session", session.value());
        fields.put("principal", principal.value());
        return (JsonText.toText(JsonText.fromPlain(fields)) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** 一批事件写完后 fsync 一次，返回落盘后的文件大小。失败时文件尾部可能残缺，由下次 {@link #open} 截掉。 */
    public long append(List<EventEnvelope> events) throws IOException {
        StringBuilder lines = new StringBuilder();
        for (EventEnvelope envelope : events) {
            if (!envelope.principal().equals(principal) || !envelope.session().equals(session)) {
                throw new IllegalArgumentException("event of " + envelope.principal().value() + "/" + envelope.session().value()
                        + " does not belong to file of " + principal.value() + "/" + session.value());
            }
            lines.append(line(envelope)).append('\n');
        }
        writeFully(channel, ByteBuffer.wrap(lines.toString().getBytes(StandardCharsets.UTF_8)));
        channel.force(true);
        if (!unsyncedDirs.isEmpty()) {
            for (Path dir : unsyncedDirs) {
                syncDir(dir);
            }
            unsyncedDirs = List.of();
        }
        return channel.size();
    }

    private String line(EventEnvelope envelope) {
        Event event = envelope.event();
        // payload 已经是 JSON 文本，直接拼接，不解析第二次。
        return "{\"seq\":" + envelope.seq()
                + ",\"at\":\"" + envelope.at()
                + "\",\"type\":" + JsonText.quote(event.type())
                + ",\"version\":" + event.version()
                + ",\"run\":" + (envelope.run() == null ? "null" : JsonText.quote(envelope.run().value()))
                + ",\"payload\":" + codec.encode(event) + "}";
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    /** 可重复调用。 */
    @Override
    public void close() throws IOException {
        channel.close();
    }
}
