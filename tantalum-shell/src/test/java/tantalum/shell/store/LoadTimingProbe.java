package tantalum.shell.store;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.PrincipalState;

/** 手动运行：-Dprobe.data=数据目录 -Dprobe.user=… -Dprobe.session=…。比较内核重放和结果行折叠一个会话的耗时。 */
class LoadTimingProbe {

    @Test
    void time() throws Exception {
        String data = System.getProperty("probe.data");
        Assumptions.assumeTrue(data != null);
        SessionPaths paths = new SessionPaths(Path.of(data));
        SessionFileReader reader = new SessionFileReader(EventCodec.withBuiltins());
        Principal user = new Principal(System.getProperty("probe.user"));
        SessionId session = new SessionId(System.getProperty("probe.session"));
        SessionReplay replay = new SessionReplay(paths, reader,
                (principal, seq) -> new UsageLedger.Cursor(PrincipalState.start(principal), java.util.List.of()), IndexFixture.kernel());
        RecordReplay records = new RecordReplay(paths, reader);
        for (int i = 0; i < 6; i++) {
            long a = System.nanoTime();
            replay.load(user, session, Long.MAX_VALUE).orElseThrow();
            long b = System.nanoTime();
            records.load(user, session, Long.MAX_VALUE).orElseThrow();
            long c = System.nanoTime();
            reader.readAll(paths.sessionFile(user, session));
            long d = System.nanoTime();
            System.out.printf("PROBE kernel %d ms, fold %d ms, read events only %d ms%n", (b - a) / 1_000_000, (c - b) / 1_000_000, (d - c) / 1_000_000);
        }
    }
}
