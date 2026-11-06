package tantalum.shell.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.json.JsonText;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 一个会话的只追加文件。第一行是头部，之后每行是处理完的一步：事件和它的结果（one-line-journal J1）。
 * {@link #appendLines} 返回才算落盘。调用方保证同一文件同时只有一个写入者。
 */
public final class SessionFile implements AutoCloseable {

    /** 3：一步一行。2（事件行之间夹着结果行）和 1（只有事件）不再读（one-line-journal J12）。 */
    public static final int FORMAT = 3;

    private final Principal principal;
    private final SessionId session;
    private final FileChannel channel;
    /** 新建时新出现的目录项所在的目录：第一批行落盘后再 fsync，省掉单独为头部做的一次 fsync。 */
    private List<Path> unsyncedDirs;

    private SessionFile(Principal principal, SessionId session, FileChannel channel) {
        this.principal = principal;
        this.session = session;
        this.channel = channel;
    }

    /**
     * 打开写入，先截掉上次崩溃留下的残缺尾部。文件不存在时才创建，所以只有第一次写入才产生文件。
     * 新文件的头部和第一批行一起 fsync：崩溃只留下残缺的头部，下次打开时截掉重写。
     */
    public static SessionFile open(SessionPaths paths, Principal principal, SessionId session) throws IOException {
        Path file = paths.sessionFile(principal, session);
        SessionFileReader.repairTail(file);
        List<Path> unsynced = List.of();
        if (!Files.exists(file) || Files.size(file) == 0) {
            unsynced = create(paths, file, principal, session);
        }
        PrivateFiles.restrict(file);
        SessionFile opened = new SessionFile(principal, session, FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND));
        opened.unsyncedDirs = unsynced;
        return opened;
    }

    /** 写头部，不 fsync。返回第一批行落盘后要 fsync 的目录。 */
    private static List<Path> create(SessionPaths paths, Path file, Principal principal, SessionId session) throws IOException {
        boolean rootIsNew = createPrivateDir(paths.sessionsDir());
        boolean userIsNew = createPrivateDir(file.getParent());
        boolean fileIsNew = !Files.exists(file);
        if (fileIsNew) {
            PrivateFiles.createFile(file);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            writeFully(channel, ByteBuffer.wrap(header(principal, session)));
        }
        // 文件内容落盘还不够，目录项也要落盘，否则崩溃后文件可能不见。
        List<Path> dirs = new ArrayList<>();
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
        PrivateFiles.createDirectories(dir);
        return true;
    }

    private static byte[] header(Principal principal, SessionId session) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("format", FORMAT);
        fields.put("session", session.value());
        fields.put("principal", principal.value());
        return (JsonText.toText(JsonText.fromPlain(fields)) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** 这一步属于这个文件。 */
    void check(EventEnvelope envelope) {
        if (!envelope.principal().equals(principal) || !envelope.session().equals(session)) {
            throw new IllegalArgumentException("event of " + envelope.principal().value() + "/" + envelope.session().value()
                    + " does not belong to file of " + principal.value() + "/" + session.value());
        }
    }

    /** 已经编码好的行（不含换行符），一次写完并 fsync，返回落盘后的文件大小。失败时尾部可能残缺，由下次 {@link #open} 截掉。 */
    long appendLines(List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        writeFully(channel, ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8)));
        channel.force(true);
        if (!unsyncedDirs.isEmpty()) {
            for (Path dir : unsyncedDirs) {
                PrivateFiles.syncDirectory(dir);
            }
            unsyncedDirs = List.of();
        }
        return channel.size();
    }

    /** 一行的事件部分：{@code {"seq":…,"payload":…}}，{@link LineCodec} 在后面接上结果。 */
    static String line(EventEnvelope envelope, String payload) {
        Event event = envelope.event();
        // payload 已经是 JSON 文本，直接拼接，不解析第二次。
        return "{\"seq\":" + envelope.seq()
                + ",\"at\":\"" + envelope.at()
                + "\",\"type\":" + JsonText.quote(event.type())
                + ",\"version\":" + event.version()
                + ",\"run\":" + (envelope.run() == null ? "null" : JsonText.quote(envelope.run().value()))
                + ",\"payload\":" + payload + "}";
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
