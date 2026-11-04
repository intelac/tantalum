package tantalum.shell.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tantalum.kernel.Kernel;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.RecordCodec;
import tantalum.shell.journal.RecordMemo;
import tantalum.shell.journal.SessionFileReader;
import tantalum.value.decision.Decision;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;

/** 手动运行：-Dprobe.dir=会话目录。用真实会话文件估算结果行让文件增大多少。 */
class RecordSizeProbe {

    @Test
    void measure() throws Exception {
        String dir = System.getProperty("probe.dir");
        Assumptions.assumeTrue(dir != null);
        EventCodec events = EventCodec.withBuiltins();
        RecordCodec codec = new RecordCodec(events);
        Kernel kernel = IndexFixture.kernel();
        long eventTotal = 0;
        long recordTotal = 0;
        try (Stream<Path> files = Files.walk(Path.of(dir))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".jsonl")).toList()) {
                List<EventEnvelope> read = new SessionFileReader(events).readAll(file);
                KernelState state = KernelState.empty();
                RecordMemo memo = RecordMemo.writer();
                long recordBytes = 0;
                for (EventEnvelope envelope : read) {
                    memo.see(events.mapper().valueToTree(envelope.event()));
                    Decision decision = kernel.step(state, envelope);
                    String line = codec.encode(StepRecord.of(envelope, state, decision), memo);
                    recordBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (System.getProperty("probe.dump") != null) {
                        System.out.println("LINE " + envelope.event().type() + " " + line.length() + " " + line.substring(0, Math.min(400, line.length())));
                    }
                    state = decision.newState();
                }
                long size = Files.size(file);
                eventTotal += size;
                recordTotal += recordBytes;
                System.out.printf("PROBE %s events=%d file=%d records=%d (+%.0f%%)%n", file.getFileName(), read.size(), size, recordBytes,
                        100.0 * recordBytes / size);
            }
        }
        System.out.printf("PROBE total file=%d records=%d (+%.0f%%)%n", eventTotal, recordTotal, 100.0 * recordTotal / eventTotal);
    }
}
