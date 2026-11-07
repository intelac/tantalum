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

    /**
     * 按文件顺序读每一行，返回读完后的 memo（读取方，{@link RecordMemo#toWriter} 后交给写入方接着用）。
     * 每行只解析一次，序号必须递增。
     */
    public RecordMemo forEachLine(Path file, Consumer<Line> consumer) throws IOException {
        RecordMemo memo = RecordMemo.reader();
        if (!Files.exists(file)) {
            return memo;
        }
        try (Lines in = new Lines(Files.newInputStream(file), MAX_LINE_BYTES)) {
            byte[] current = in.next();
            if (current == null) {
                return memo;
            }
            byte[] next = in.next();
            Identity identity = parseHeader(file, current, next == null);
            if (identity == null) {
                return memo;
            }
            memo = RecordMemo.reader(identity.principal(), identity.session());
            long lineNumber = 2;
            long lastSeq = Long.MIN_VALUE;
            current = next;
            while (current != null) {
                next = in.next();
                String text = text(current);
                JsonNode tree = text == null ? null : parseOrNull(text);
                if (tree == null || !tree.isObject()) {
                    if (next == null) {
                        LOG.warn("ignoring incomplete last line {} of {}", lineNumber, file);
                        return memo;
                    }
                    throw corrupt(file, lineNumber, "not a JSON object", null);
                }
                Line line;
                try {
                    line = lines.decode(tree, identity.principal(), identity.session(), memo);
                } catch (IllegalArgumentException | ArithmeticException failure) {
                    throw corrupt(file, lineNumber, failure.getMessage(), failure);
                }
                if (line.seq() <= lastSeq) {
                    throw corrupt(file, lineNumber, "seq " + line.seq() + " is not after " + lastSeq, null);
                }
                lastSeq = line.seq();
                consumer.accept(line);
                current = next;
                lineNumber++;
            }
        }
        return memo;
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

        Lines(InputStream in, int maxLine) {
            this.in = in;
            this.maxLine = maxLine;
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
            throw corrupt(file, 1, "format " + format.value() + " is from before one line per step; this program no longer reads it", null);
        }
        return new Identity(new Principal(principal.value()), new SessionId(session.value()));
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
                if (parse(bytes(channel, start, end - 1)) instanceof Json.Obj record
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
