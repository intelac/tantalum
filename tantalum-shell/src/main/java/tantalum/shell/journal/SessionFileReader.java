package tantalum.shell.journal;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
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
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFile;
import tantalum.shell.json.JsonText;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * 读会话文件。崩溃只会弄坏最后一行，所以尾部残缺不算错误：读取时忽略，打开写入前截掉。
 * 其他位置的损坏说明文件被改过，抛 {@link SessionCorruptException}。
 */
public final class SessionFileReader {

    private static final Logger LOG = LoggerFactory.getLogger(SessionFileReader.class);
    private static final int TAIL_CHUNK = 64 * 1024;

    /** 收到一个事件和它那一行结束后的文件位置。 */
    @FunctionalInterface
    public interface Visitor {
        void visit(EventEnvelope envelope, long endOffset);
    }

    private final EventCodec codec;

    public SessionFileReader(EventCodec codec) {
        this.codec = codec;
    }

    public List<EventEnvelope> readAll(Path file) throws IOException {
        List<EventEnvelope> events = new ArrayList<>();
        forEach(file, events::add);
        return events;
    }

    /** 不修改文件，可以和写入同时进行；正在写的尾部不会被读到。文件不存在时没有事件。 */
    public void forEach(Path file, Consumer<EventEnvelope> consumer) throws IOException {
        scan(file, 0, (envelope, end) -> consumer.accept(envelope));
    }

    /** 从文件位置 {@code from} 开始读，它必须是一行的开头；0 表示头部之后。头部总要读，事件的身份在那里。 */
    public void scan(Path file, long from, Visitor visitor) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] current = readLine(in);
            if (current == null) {
                return;
            }
            long position = current.length + 1L;
            if (from > position) {
                in.skipNBytes(from - position);
                position = from;
            }
            byte[] next = readLine(in);
            Identity identity = parseHeader(file, current, next == null);
            if (identity == null) {
                return;
            }
            long lineNumber = 2;
            long lastSeq = Long.MIN_VALUE;
            current = next;
            while (current != null) {
                next = readLine(in);
                if (next == null && !isValidRecord(current)) {
                    LOG.warn("ignoring incomplete last line {} of {}", lineNumber, file);
                    return;
                }
                EventEnvelope envelope = parseEvent(file, lineNumber, current, identity);
                if (envelope.seq() <= lastSeq) {
                    throw corrupt(file, lineNumber, "seq " + envelope.seq() + " is not after " + lastSeq, null);
                }
                lastSeq = envelope.seq();
                position += current.length + 1L;
                visitor.visit(envelope, position);
                current = next;
                lineNumber++;
            }
        }
    }

    /** 读一行，不含换行符。没有换行符的尾部不是完整的记录，当作不存在。 */
    private static byte[] readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return line.toByteArray();
            }
            line.write(b);
        }
        return null;
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
        return new Identity(new Principal(principal.value()), new SessionId(session.value()));
    }

    private EventEnvelope parseEvent(Path file, long lineNumber, byte[] line, Identity identity) {
        if (!(parse(line) instanceof Json.Obj record)
                || !(record.fields().get("seq") instanceof Json.Num seq)
                || !(record.fields().get("at") instanceof Json.Str at)
                || !(record.fields().get("type") instanceof Json.Str type)
                || !(record.fields().get("version") instanceof Json.Num version)
                || !(record.fields().get("payload") instanceof Json payload)) {
            throw corrupt(file, lineNumber, "not an event record", null);
        }
        try {
            Event event = codec.decode(type.value(), version.value().intValueExact(), JsonText.toText(payload));
            RunId run = record.fields().get("run") instanceof Json.Str value ? new RunId(value.value()) : null;
            return new EventEnvelope(seq.value().longValueExact(), Instant.parse(at.value()), identity.principal(), identity.session(), run, event);
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException failure) {
            throw corrupt(file, lineNumber, failure.getMessage(), failure);
        }
    }

    private static SessionCorruptException corrupt(Path file, long lineNumber, String detail, Throwable cause) {
        return new SessionCorruptException("session file " + file + " line " + lineNumber + ": " + detail, cause);
    }

    /** 严格按 UTF-8 解码，坏字节不会被替换成别的字符。 */
    private static Json parse(byte[] line) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(line))
                    .toString();
            return JsonText.parseOrNull(text);
        } catch (CharacterCodingException malformed) {
            return null;
        }
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

    /** 在 {@code offset} 结束的那一行的序号。{@code offset} 不是行尾或那一行不是事件时为 -1。 */
    public static long seqEndingAt(Path file, long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (offset <= 0 || offset > channel.size() || byteAt(channel, offset - 1) != '\n') {
                return -1;
            }
            long start = lineStart(channel, offset - 1);
            return parse(bytes(channel, start, offset - 1)) instanceof Json.Obj record
                    && record.fields().get("seq") instanceof Json.Num seq ? seq.value().longValueExact() : -1;
        }
    }

    /** 序号为 {@code seq} 的那一行结束的文件位置，没有时为 -1。从末尾往前找，所以落后不多时很快。 */
    public static long endOffsetOf(Path file, long seq) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long end = channel.size();
            if (end > 0 && byteAt(channel, end - 1) != '\n') {
                end = lineStart(channel, end);
            }
            while (end > 0) {
                long start = lineStart(channel, end - 1);
                if (parse(bytes(channel, start, end - 1)) instanceof Json.Obj record && record.fields().get("seq") instanceof Json.Num found) {
                    long value = found.value().longValueExact();
                    if (value <= seq) {
                        return value == seq ? end : -1;
                    }
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
