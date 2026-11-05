package tantalum.shell.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.Line;
import tantalum.shell.journal.SessionFiles;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.decision.Decision;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;
import tools.jackson.databind.JsonNode;

/**
 * 手动运行：-Dprobe.dir=旧会话目录（record-results 之前、只有事件行的会话文件）。用当前内核处理这些事件，
 * 按现在的格式（一步一行、配上的东西只记变化）重新写一遍，和原来的文件大小比较（one-line-journal 需求 7）。只读这个目录。
 */
class RealSizeProbe {

    @TempDir
    Path out;

    @Test
    void measure() throws Exception {
        String dir = System.getProperty("probe.dir");
        Assumptions.assumeTrue(dir != null);
        EventCodec events = EventCodec.withBuiltins();
        Kernel kernel = IndexFixture.kernel();
        SessionPaths paths = new SessionPaths(out);
        long before = 0;
        long after = 0;
        try (Stream<Path> found = Files.walk(Path.of(dir));
             SessionFiles files = new SessionFiles(paths, events, 16, (principal, session, seq, offset) -> { })) {
            for (Path file : found.filter(f -> f.toString().endsWith(".jsonl")).sorted().toList()) {
                List<EventEnvelope> read = oldEvents(file, events);
                KernelState state = KernelState.empty();
                for (EventEnvelope envelope : read) {
                    Decision decision = kernel.step(state, envelope);
                    files.append(envelope.principal(), envelope.session(), List.of(new Line(envelope, StepRecord.of(envelope, state, decision))));
                    state = decision.newState();
                }
                long size = Files.size(file);
                long written = read.isEmpty() ? 0 : Files.size(paths.sessionFile(read.getFirst().principal(), read.getFirst().session()));
                before += size;
                after += written;
                System.out.printf("PROBE %s events=%d before=%d after=%d (%.1f%%)%n", file.getFileName(), read.size(), size, written,
                        100.0 * written / size);
            }
        }
        System.out.printf("PROBE total before=%d after=%d (%.1f%%)%n", before, after, 100.0 * after / before);
    }

    /** 旧文件只有事件行：头部之后每行一个事件。组装上下文以前是第 1 版，没有工具来源，按现在的类读。 */
    private static List<EventEnvelope> oldEvents(Path file, EventCodec events) throws Exception {
        List<String> lines = Files.readAllLines(file);
        JsonNode header = events.mapper().readTree(lines.getFirst());
        Principal principal = new Principal(header.get("principal").asString());
        SessionId session = new SessionId(header.get("session").asString());
        List<EventEnvelope> read = new ArrayList<>();
        for (String text : lines.subList(1, lines.size())) {
            JsonNode line = events.mapper().readTree(text);
            if (line.has("result")) {
                continue;
            }
            String type = line.get("type").asString();
            Event event = type.equals(ContextAssembled.TYPE)
                    ? events.mapper().treeToValue(line.get("payload"), ContextAssembled.class)
                    : events.decode(type, line.get("version").asInt(), line.get("payload"));
            RunId run = line.path("run").isString() ? new RunId(line.get("run").asString()) : null;
            read.add(new EventEnvelope(line.get("seq").asLong(), Instant.parse(line.get("at").asString()), principal, session, run, event));
        }
        return read;
    }
}
