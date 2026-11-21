package tantalum.shell.journal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.json.JsonText;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * 读会话文件。崩溃只会弄坏最后一行，所以尾部残缺不算错误：读取时忽略，打开写入前截掉。
 * 其他位置的损坏说明文件被改过，抛 {@link SessionCorruptException}。
 */
public final class SessionFileReader {

    private static final Logger LOG = LoggerFactory.getLogger(SessionFileReader.class);
    private static final int TAIL_CHUNK = 64 * 1024;

    private final LineCodec lines;

    public SessionFileReader(EventCodec codec) {
        this.lines = new LineCodec(codec);
    }

    public List<EventEnvelope> readAll(Path file) throws IOException {
        List<EventEnvelope> events = new ArrayList<>();
        forEach(file, events::add);
        return events;
    }

    /** 只要事件（检查工具按事件重放）。不修改文件，可以和写入同时进行；正在写的尾部不会被读到。文件不存在时没有事件。 */
    public void forEach(Path file, Consumer<EventEnvelope> consumer) throws IOException {
        forEachLine(file, line -> consumer.accept(line.envelope()));
    }

    /** 读的人看到的两种行：一步，和一份快照（{@code offset} 是快照行在文件里的位置）。 */
    public interface Visitor {
        void step(Line line);

        default void snapshot(Snapshot snapshot, long offset) {
        }
    }

    /**
     * 按文件顺序读每一步，返回读完后的 memo（读取方，{@link RecordMemo#toWriter} 后交给写入方接着用）。
     * 每行只解析一次，序号必须递增。快照行不交给 {@code consumer}，只在那里换 memo。
     */
    public RecordMemo forEachLine(Path file, Consumer<Line> consumer) throws IOException {
        return forEachEntry(file, 0, consumer::accept);
    }

    /**
     * 同上，从 {@code from} 读起：0 是文件头之后；不是 0 时那里必须是一行快照的开头（{@link #lastSnapshot} 找到的、索引记着的），
     * 用它起头，之后的行只引用它和它之后的内容。快照行也交给访问者。
     */
    public RecordMemo forEachEntry(Path file, long from, Visitor visitor) throws IOException {
        RecordMemo memo = RecordMemo.reader();
        if (!Files.exists(file)) {
            return memo;
        }
        Identity identity;
        long headerBytes;
        try (Lines in = new Lines(Files.newInputStream(file), MAX_LINE_BYTES, 0)) {
            byte[] header = in.next();
            if (header == null) {
                return memo;
            }
            identity = parseHeader(file, header, in.next() == null);
            if (identity == null) {
                return memo;
            }
            headerBytes = header.length + 1L;
        }
        memo = RecordMemo.reader(identity.principal(), identity.session());
        long start = from == 0 ? headerBytes : from;
        InputStream stream = Files.newInputStream(file);
        stream.skipNBytes(start);
        try (Lines in = new Lines(stream, MAX_LINE_BYTES, start)) {
            long lastSeq = Long.MIN_VALUE;
            boolean first = true;
            // 从头读时知道第几行（文件头是第 1 行）；从中间读起时只知道位置
            long lineNumber = from == 0 ? 2 : -1;
            byte[] current = in.next();
            long currentAt = in.lastStart();
            while (current != null) {
                byte[] next = in.next();
                long nextAt = in.lastStart();
                String text = text(current);
                JsonNode tree = text == null ? null : parseOrNull(text);
                if (tree == null || !tree.isObject()) {
                    if (next == null) {
                        LOG.warn("ignoring incomplete last line at offset {} of {}", currentAt, file);
                        return memo;
                    }
                    throw corruptAt(file, lineNumber, currentAt, "not a JSON object", null);
                }
                if (lines.isSnapshot(tree)) {
                    Snapshot snapshot;
                    try {
                        snapshot = lines.decodeSnapshot(tree);
                    } catch (IllegalArgumentException failure) {
                        throw corruptAt(file, lineNumber, currentAt, failure.getMessage(), failure);
                    }
                    if (!first && snapshot.seq() != lastSeq) {
                        throw corruptAt(file, lineNumber, currentAt, "snapshot of seq " + snapshot.seq() + " follows seq " + lastSeq, null);
                    }
                    lastSeq = snapshot.seq();
                    memo = RecordMemo.afterSnapshot(true, identity.principal(), identity.session(), snapshot);
                    visitor.snapshot(snapshot, currentAt);
                } else {
                    if (first && from != 0) {
                        throw corruptAt(file, lineNumber, currentAt, "reading from here needs a snapshot line", null);
                    }
                    Line line;
                    try {
                        line = lines.decode(tree, identity.principal(), identity.session(), memo);
                    } catch (IllegalArgumentException | ArithmeticException failure) {
                        throw corruptAt(file, lineNumber, currentAt, failure.getMessage(), failure);
                    }
                    if (line.seq() <= lastSeq) {
                        throw corruptAt(file, lineNumber, currentAt, "seq " + line.seq() + " is not after " + lastSeq, null);
                    }
                    lastSeq = line.seq();
                    visitor.step(line);
                }
                first = false;
                current = next;
                currentAt = nextAt;
                if (lineNumber > 0) {
                    lineNumber++;
                }
            }
        }
        return memo;
    }

    /**
     * 最后一份快照行在文件里的位置；没有时为 -1。从尾部往前一块一块读（每块读一次），看块里每个换行符之后那一行的开头，不解析。
     * 尾部残缺的行不算。快照行的开头在块尾附近、被切开时，按位置再读那几个字节。
     */
    public static long lastSnapshot(Path file) throws IOException {
        if (!Files.exists(file)) {
            return -1;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long end = channel.size();
            if (end > 0 && byteAt(channel, end - 1) != '\n') {
                end = lineStart(channel, end);
            }
            // [0, end) 是完整的行；行的开头是 0 和每个换行符的下一个字节（最后一个换行符之后是 end，不是行）
            long chunkEnd = end - 1;
            while (chunkEnd > 0) {
                long begin = Math.max(0, chunkEnd - TAIL_CHUNK);
                byte[] chunk = bytes(channel, begin, chunkEnd);
                for (int i = chunk.length - 1; i >= 0; i--) {
                    if (chunk[i] != '\n') {
                        continue;
                    }
                    long start = begin + i + 1;
                    boolean whole = i + 1 + SnapshotCodec.PREFIX.length <= chunk.length;
                    if (whole ? SnapshotCodec.isSnapshot(java.util.Arrays.copyOfRange(chunk, i + 1, i + 1 + SnapshotCodec.PREFIX.length))
                            : isSnapshotAt(channel, start, end)) {
                        return start;
                    }
                }
                chunkEnd = begin;
            }
            return end > 0 && isSnapshotAt(channel, 0, end) ? 0 : -1;
        }
    }

    /** 从 {@code offset} 开始的那一行有多长（含换行符）。 */
    public static long lineLength(Path file, long offset) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(offset);
            long length = 0;
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) {
                for (int i = 0; i < read; i++) {
                    if (chunk[i] == '\n') {
                        return length + i + 1;
                    }
                }
                length += read;
            }
            return length;
        }
    }

    private static boolean isSnapshotAt(FileChannel channel, long start, long end) throws IOException {
        long length = Math.min(end - start, SnapshotCodec.PREFIX.length);
        return length == SnapshotCodec.PREFIX.length && SnapshotCodec.isSnapshot(bytes(channel, start, start + length));
    }

    private JsonNode parseOrNull(String text) {
        try {
            return lines.parse(text);
        } catch (IllegalArgumentException notJson) {
            return null;
        }
    }

    /** 按块读、找换行符：比逐字节读快得多（长会话的结果行几十 KB）。没有换行符的尾部不是完整的记录，当作不存在。 */
    /** 一行的上限：超过的不是我们写的文件，当作损坏，不把整个文件读进内存。 */
    static final int MAX_LINE_BYTES = 64 * 1024 * 1024;

    static final class Lines implements AutoCloseable {
        private final InputStream in;
        private byte[] buffer = new byte[64 * 1024];
        private int start;
        private int end;
        private boolean eof;

        private final int maxLine;
        /** 下一行开头在文件里的位置，和刚交出去的那一行开头的位置。 */
        private long position;
        private long lastStart;

        Lines(InputStream in, int maxLine, long position) {
            this.in = in;
            this.maxLine = maxLine;
            this.position = position;
            this.lastStart = position;
        }

        /** 最近一次 {@link #next} 交出的那一行开头在文件里的位置。 */
        long lastStart() {
            return lastStart;
        }

        /** 下一行，不含换行符；没有完整的行时为 null。 */
        byte[] next() throws IOException {
            int scanned = start;
            while (true) {
                for (int i = scanned; i < end; i++) {
                    if (buffer[i] == '\n') {
                        if (i - start > maxLine) {
                            throw new SessionCorruptException("a line longer than " + maxLine + " bytes", null);
                        }
                        byte[] line = java.util.Arrays.copyOfRange(buffer, start, i);
                        start = i + 1;
                        lastStart = position;
                        position += line.length + 1;
                        return line;
                    }
                }
                if (end - start > maxLine) {
                    throw new SessionCorruptException("a line longer than " + maxLine + " bytes", null);
                }
                if (eof) {
                    return null;
                }
                scanned = end - start;
                if (start > 0) {
                    System.arraycopy(buffer, start, buffer, 0, end - start);
                    end -= start;
                    start = 0;
                }
                if (end == buffer.length) {
                    if (buffer.length >= maxLine) {
                        throw new SessionCorruptException("a line longer than " + maxLine + " bytes", null);
                    }
                    buffer = java.util.Arrays.copyOf(buffer, Math.min(maxLine, buffer.length * 2));
                }
                int read = in.read(buffer, end, buffer.length - end);
                if (read < 0) {
                    eof = true;
                } else {
                    end += read;
                }
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private record Identity(Principal principal, SessionId session) {}

    private static Identity parseHeader(Path file, byte[] line, boolean isLastLine) {
        Json parsed = parse(line);
        if (!(parsed instanceof Json.Obj header) || !(header.fields().get("format") instanceof Json.Num format)
                || !(header.fields().get("session") instanceof Json.Str session)
                || !(header.fields().get("principal") instanceof Json.Str principal)) {
            if (isLastLine) {
                return null;
            }
            throw corrupt(file, 1, "invalid header", null);
        }
        if (format.value().intValue() > SessionFile.FORMAT) {
            throw corrupt(file, 1, "format " + format.value() + " is newer than this program supports", null);
        }
        if (format.value().intValue() < SessionFile.FORMAT) {
            throw corrupt(file, 1, "format " + format.value() + " is older than this program reads (" + SessionFile.FORMAT + ")", null);
        }
        return new Identity(new Principal(principal.value()), new SessionId(session.value()));
    }

    private static SessionCorruptException corruptAt(Path file, long lineNumber, long offset, String detail, Throwable cause) {
        return new SessionCorruptException("session file " + file + (lineNumber > 0 ? " line " + lineNumber + " (offset " + offset + ")"
                : " at offset " + offset) + ": " + detail, cause);
    }

    private static SessionCorruptException corrupt(Path file, long lineNumber, String detail, Throwable cause) {
        return new SessionCorruptException("session file " + file + " line " + lineNumber + ": " + detail, cause);
    }

    /** 严格按 UTF-8 解码，坏字节不会被替换成别的字符；有坏字节时为 null。 */
    private static String text(byte[] line) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(line))
                    .toString();
        } catch (CharacterCodingException malformed) {
            return null;
        }
    }

    private static Json parse(byte[] line) {
        String text = text(line);
        return text == null ? null : JsonText.parseOrNull(text);
    }

    private static boolean isValidRecord(byte[] line) {
        return parse(line) instanceof Json.Obj;
    }

    /**
     * 截掉没有换行符的尾部，以及不是合法 JSON 对象的最后一行。只看 JSON 是否完整，不解码事件：
     * 不认识的事件类型是完整的记录，不能因此丢掉。文件不存在时什么也不做。
     */
    static void repairTail(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long size = channel.size();
            if (size == 0) {
                return;
            }
            long end;
            if (byteAt(channel, size - 1) != '\n') {
                end = lineStart(channel, size);
            } else {
                long lastLine = lineStart(channel, size - 1);
                end = isValidRecord(bytes(channel, lastLine, size - 1)) ? size : lastLine;
            }
            if (end < size) {
                LOG.warn("truncating incomplete tail of {}: {} bytes at offset {}", file, size - end, end);
                channel.truncate(end);
                channel.force(true);
            }
        }
    }

    /** 文件里最后一个完整事件的序号，没有时为 -1。尾部残缺的行不算。不读其他内容。 */
    public static long lastSeq(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long end = channel.size();
            if (end > 0 && byteAt(channel, end - 1) != '\n') {
                end = lineStart(channel, end);
            }
            while (end > 0) {
                long start = lineStart(channel, end - 1);
                if (!isSnapshotAt(channel, start, end - 1) && parse(bytes(channel, start, end - 1)) instanceof Json.Obj record
                        && record.fields().get("seq") instanceof Json.Num seq) {
                    return seq.value().longValueExact();
                }
                end = start;
            }
            return -1;
        }
    }

    /** {@code position} 所在行的起点，即它之前最后一个换行符的下一个字节。 */
    private static long lineStart(FileChannel channel, long position) throws IOException {
        long end = position;
        while (end > 0) {
            long begin = Math.max(0, end - TAIL_CHUNK);
            byte[] chunk = bytes(channel, begin, end);
            for (int i = chunk.length - 1; i >= 0; i--) {
                if (chunk[i] == '\n') {
                    return begin + i + 1;
                }
            }
            end = begin;
        }
        return 0;
    }

    private static int byteAt(FileChannel channel, long position) throws IOException {
        return bytes(channel, position, position + 1)[0];
    }

    private static byte[] bytes(FileChannel channel, long begin, long end) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) (end - begin));
        long position = begin;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("unexpected end of file at " + position);
            }
            position += read;
        }
        return buffer.array();
    }
}
