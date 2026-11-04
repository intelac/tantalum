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
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private final EventCodec codec;
    private final RecordCodec records;

    public SessionFileReader(EventCodec codec) {
        this.codec = codec;
        this.records = new RecordCodec(codec);
    }

    public List<EventEnvelope> readAll(Path file) throws IOException {
        List<EventEnvelope> events = new ArrayList<>();
        forEach(file, events::add);
        return events;
    }

    /** 只读事件行，结果行跳过。不修改文件，可以和写入同时进行；正在写的尾部不会被读到。文件不存在时没有事件。 */
    public void forEach(Path file, Consumer<EventEnvelope> consumer) throws IOException {
        read(file, false, line -> consumer.accept(((Line.Event) line).envelope()));
    }

    /**
     * 按文件顺序读事件行和结果行，返回读完后的 memo（读取方，{@link RecordMemo#toWriter} 后交给写入方接着用）。
     * 结果行的序号必须递增，并且对应一个前面读到、还没有结果的事件。
     */
    public RecordMemo forEachLine(Path file, Consumer<Line> consumer) throws IOException {
        return read(file, true, consumer);
    }

    private RecordMemo read(Path file, boolean results, Consumer<Line> consumer) throws IOException {
        RecordMemo memo = RecordMemo.reader();
        if (!Files.exists(file)) {
            return memo;
        }
        try (Lines in = new Lines(Files.newInputStream(file))) {
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
            long lastResult = Long.MIN_VALUE;
            Set<Long> unanswered = new HashSet<>();
            current = next;
            while (current != null) {
                next = in.next();
                long skipped = results ? -1 : resultSeq(current);
                if (skipped >= 0) {
                    // 只读事件时不解析结果行：序号在行首，照样检查配对
                    if (skipped <= lastResult || !unanswered.remove(skipped)) {
                        throw corrupt(file, lineNumber, "result for seq " + skipped + " has no earlier event without a result", null);
                    }
                    lastResult = skipped;
                    current = next;
                    lineNumber++;
                    continue;
                }
                String text = text(current);
                Json parsed = text == null ? null : JsonText.parseOrNull(text);
                if (!(parsed instanceof Json.Obj record)) {
                    if (next == null) {
                        LOG.warn("ignoring incomplete last line {} of {}", lineNumber, file);
                        return memo;
                    }
                    throw corrupt(file, lineNumber, "not a JSON object", null);
                }
                if (record.fields().get("result") instanceof Json.Num result) {
                    long seq = exactSeq(file, lineNumber, result);
                    if (seq <= lastResult || !unanswered.remove(seq)) {
                        throw corrupt(file, lineNumber, "result for seq " + seq + " has no earlier event without a result", null);
                    }
                    lastResult = seq;
                    if (results) {
                        try {
                            RecordCodec.Decoded decoded = records.decode(text, memo);
                            consumer.accept(new Line.Result(decoded.record(), decoded.fingerprints()));
                        } catch (IllegalArgumentException failure) {
                            throw corrupt(file, lineNumber, failure.getMessage(), failure);
                        }
                    }
                } else {
                    EventEnvelope envelope = parseEvent(file, lineNumber, record, identity);
                    if (envelope.seq() <= lastSeq) {
                        throw corrupt(file, lineNumber, "seq " + envelope.seq() + " is not after " + lastSeq, null);
                    }
                    lastSeq = envelope.seq();
                    unanswered.add(lastSeq);
                    if (results) {
                        memo.seeJson(record.fields().get("payload"));
                        memo.seeEvent(envelope);
                    }
                    consumer.accept(new Line.Event(envelope));
                }
                current = next;
                lineNumber++;
            }
        }
        return memo;
    }

    private static final byte[] RESULT_PREFIX = "{\"result\":".getBytes(StandardCharsets.US_ASCII);

    /** 结果行（{@code RecordCodec} 写的，序号总在行首）的序号；不是结果行时为 -1。 */
    private static long resultSeq(byte[] line) {
        if (line.length <= RESULT_PREFIX.length || !java.util.Arrays.equals(line, 0, RESULT_PREFIX.length, RESULT_PREFIX, 0, RESULT_PREFIX.length)) {
            return -1;
        }
        long seq = 0;
        int i = RESULT_PREFIX.length;
        int digits = 0;
        while (i < line.length && line[i] >= '0' && line[i] <= '9' && digits < 18) {
            seq = seq * 10 + (line[i++] - '0');
            digits++;
        }
        return digits > 0 && i < line.length && (line[i] == ',' || line[i] == '}') ? seq : -1;
    }

    private static long exactSeq(Path file, long lineNumber, Json.Num seq) {
        try {
            return seq.value().longValueExact();
        } catch (ArithmeticException failure) {
            throw corrupt(file, lineNumber, "seq is not an integer", failure);
        }
    }

    /** 按块读、找换行符：比逐字节读快得多（长会话的结果行几十 KB）。没有换行符的尾部不是完整的记录，当作不存在。 */
    private static final class Lines implements AutoCloseable {
        private final InputStream in;
        private byte[] buffer = new byte[64 * 1024];
        private int start;
        private int end;
        private boolean eof;

        Lines(InputStream in) {
            this.in = in;
        }

        /** 下一行，不含换行符；没有完整的行时为 null。 */
        byte[] next() throws IOException {
            int scanned = start;
            while (true) {
                for (int i = scanned; i < end; i++) {
                    if (buffer[i] == '\n') {
                        byte[] line = java.util.Arrays.copyOfRange(buffer, start, i);
                        start = i + 1;
                        return line;
                    }
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
                    buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2);
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
            throw corrupt(file, 1, "format " + format.value() + " is from before result lines; this program no longer reads it", null);
        }
        return new Identity(new Principal(principal.value()), new SessionId(session.value()));
    }

    private EventEnvelope parseEvent(Path file, long lineNumber, Json.Obj record, Identity identity) {
        if (!(record.fields().get("seq") instanceof Json.Num seq)
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
