package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.Line;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionFiles;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.journal.Snapshot;
import tantalum.shell.loop.Projections;
import tantalum.shell.loop.SessionSlice;
import tantalum.shell.loop.Snapshots;
import tantalum.shell.trace.Trace;
import tantalum.value.decision.Decision;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;

/**
 * journal-snapshots 需求 1、2：账里每隔几步一份快照。从最后一份快照载入和从头折叠一样；每一步读出来和不带快照时一样；
 * 快照之前的行全删掉也读得懂；从快照载入以后接着写，读出来也一样。用 {@link ReplayScenario} 的场景：工具轮带思考、压缩、
 * 两个用户交错、额度用完。
 */
class SnapshotLoadTest {

    private static final EventCodec CODEC = EventCodec.withBuiltins();
    private static final Snapshots SNAPSHOTS = new Snapshots(CODEC.mapper());

    @TempDir
    Path temp;

    private SessionPaths original;

    @BeforeEach
    void writeTheScenario() throws Exception {
        try (ReplayScenario scenario = new ReplayScenario(Files.createDirectories(temp.resolve("original")))) {
            scenario.run();
        }
        original = new SessionPaths(temp.resolve("original"));
    }

    private static RecordReplay replay(SessionPaths paths) {
        return new RecordReplay(paths, new SessionFileReader(CODEC), SNAPSHOTS);
    }

    private static List<Line> lines(SessionPaths paths, Principal principal, SessionId session) throws Exception {
        List<Line> lines = new ArrayList<>();
        new SessionFileReader(CODEC).forEachLine(paths.sessionFile(principal, session), lines::add);
        return lines;
    }

    /**
     * 把原来的账重写一遍，每 {@code every} 步之后带一份快照，就像实时写的时候存储要了快照：快照是那一步之后的状态和两个投影，
     * 之后的行用快照起头的 memo 编码。返回写了几份快照。
     */
    private static int rewrite(List<Line> lines, SessionPaths target, Principal principal, SessionId session, int every, int upTo)
            throws Exception {
        KernelState state = KernelState.empty();
        Projections projections = new Projections(new Transcript(), new Trace());
        int snapshots = 0;
        try (SessionFiles files = new SessionFiles(target, CODEC, 4, (p, s, seq, offset) -> { })) {
            List<Line> chunk = new ArrayList<>();
            for (int i = 0; i < upTo; i++) {
                Line line = lines.get(i);
                Decision decision = line.record().apply(line.envelope(), state);
                projections.apply(line.envelope(), state, decision);
                state = decision.newState();
                chunk.add(new Line(line.envelope(), line.record()));
                if ((i + 1) % every == 0) {
                    Snapshot snapshot = SNAPSHOTS.of(projections.export(state, session, line.seq()));
                    files.append(principal, session, chunk, snapshot);
                    chunk.clear();
                    snapshots++;
                }
            }
            if (!chunk.isEmpty()) {
                files.append(principal, session, chunk, null);
            }
        }
        return snapshots;
    }

    private static SessionSlice load(SessionPaths paths, Principal principal, SessionId session) throws Exception {
        return replay(paths).load(principal, session, Long.MAX_VALUE).orElseThrow().slice();
    }

    @Test
    void loadingFromTheLastSnapshotGivesWhatFoldingEverythingGives() throws Exception {
        for (var target : List.of(new Object[] {ReplayScenario.ALICE, ReplayScenario.TOOLS}, new Object[] {ReplayScenario.ALICE, ReplayScenario.COMPACTED},
                new Object[] {ReplayScenario.BOB, ReplayScenario.BOBS})) {
            Principal principal = (Principal) target[0];
            SessionId session = (SessionId) target[1];
            List<Line> lines = lines(original, principal, session);
            for (int every : new int[] {1, 2, 3, 5}) {
                SessionPaths snapshotted = new SessionPaths(temp.resolve(session.value() + "-every-" + every));
                int written = rewrite(lines, snapshotted, principal, session, every, lines.size());
                assertTrue(written > 0, session.value() + " every " + every);

                RecordReplay.Loaded loaded = replay(snapshotted).load(principal, session, Long.MAX_VALUE).orElseThrow();
                assertTrue(loaded.from() > 0, "loading starts at a snapshot: " + session.value() + " every " + every);
                assertEquals(load(original, principal, session), loaded.slice(), session.value() + " every " + every);
            }
        }
    }

    /** 要每一步的人（复核、轨迹、轮次）从头读：读到快照时换 memo，每一步和不带快照时一样。 */
    @Test
    void everyStepReadsBackTheSameAcrossSnapshots() throws Exception {
        Principal principal = ReplayScenario.ALICE;
        SessionId session = ReplayScenario.COMPACTED;
        List<Line> lines = lines(original, principal, session);
        SessionPaths snapshotted = new SessionPaths(temp.resolve("steps"));
        rewrite(lines, snapshotted, principal, session, 2, lines.size());

        List<Line> again = lines(snapshotted, principal, session);
        assertEquals(lines.size(), again.size());
        for (int i = 0; i < lines.size(); i++) {
            assertEquals(lines.get(i).envelope(), again.get(i).envelope(), "event " + i);
            assertEquals(lines.get(i).record(), again.get(i).record(), "result " + i);
        }
    }

    /** 快照之后的行只引用快照和它之后的内容：把最后一份快照之前的行全删掉，照样读得懂，结果一样。 */
    @Test
    void theLinesAfterASnapshotNeedNothingBeforeIt() throws Exception {
        Principal principal = ReplayScenario.ALICE;
        SessionId session = ReplayScenario.TOOLS;
        List<Line> lines = lines(original, principal, session);
        SessionPaths snapshotted = new SessionPaths(temp.resolve("full"));
        rewrite(lines, snapshotted, principal, session, 3, lines.size());

        Path file = snapshotted.sessionFile(principal, session);
        byte[] bytes = Files.readAllBytes(file);
        long from = SessionFileReader.lastSnapshot(file);
        int headerEnd = 0;
        while (bytes[headerEnd] != '\n') {
            headerEnd++;
        }
        SessionPaths tail = new SessionPaths(temp.resolve("tail"));
        Path cut = tail.sessionFile(principal, session);
        Files.createDirectories(cut.getParent());
        byte[] kept = new byte[headerEnd + 1 + (int) (bytes.length - from)];
        System.arraycopy(bytes, 0, kept, 0, headerEnd + 1);
        System.arraycopy(bytes, (int) from, kept, headerEnd + 1, (int) (bytes.length - from));
        Files.write(cut, kept);

        assertEquals(load(original, principal, session), load(tail, principal, session));
    }

    /** 冷载入从快照起，memo 交给写入方接着写：之后写下的行读出来和一直写下来的一样。 */
    @Test
    void writingGoesOnAfterLoadingFromASnapshot() throws Exception {
        Principal principal = ReplayScenario.ALICE;
        SessionId session = ReplayScenario.COMPACTED;
        List<Line> lines = lines(original, principal, session);
        int half = lines.size() / 2;
        SessionPaths resumed = new SessionPaths(temp.resolve("resumed"));
        assertTrue(rewrite(lines, resumed, principal, session, 2, half) > 0);

        RecordReplay.Loaded loaded = replay(resumed).load(principal, session, Long.MAX_VALUE).orElseThrow();
        try (SessionFiles files = new SessionFiles(resumed, CODEC, 4, (p, s, seq, offset) -> { })) {
            files.adopt(principal, session, loaded.memo(), loaded.from(), loaded.snapshotBytes());
            List<Line> rest = new ArrayList<>();
            for (Line line : lines.subList(half, lines.size())) {
                rest.add(new Line(line.envelope(), line.record()));
            }
            files.append(principal, session, rest, null);
        }

        assertEquals(load(original, principal, session), load(resumed, principal, session));
        List<Line> again = lines(resumed, principal, session);
        for (int i = 0; i < lines.size(); i++) {
            assertEquals(lines.get(i).record(), again.get(i).record(), "result " + i);
        }
    }

    /** 复核：快照是账的一部分。手改一份快照（这里改了历史里的一句话），复核报出它跟着的那一步；没改的报空。 */
    @Test
    void aSnapshotThatDoesNotMatchTheStepsBeforeItIsReported() throws Exception {
        Principal principal = ReplayScenario.ALICE;
        SessionId session = ReplayScenario.COMPACTED;
        List<Line> lines = lines(original, principal, session);
        SessionPaths snapshotted = new SessionPaths(temp.resolve("checked"));
        rewrite(lines, snapshotted, principal, session, 2, lines.size());
        assertEquals(java.util.OptionalLong.empty(), replay(snapshotted).firstWrongSnapshot(principal, session));

        Path file = snapshotted.sessionFile(principal, session);
        List<String> text = new ArrayList<>(Files.readAllLines(file));
        int at = -1;
        for (int i = 0; i < text.size(); i++) {
            if (text.get(i).startsWith("{\"snapshot\":") && text.get(i).contains("\"first ")) {
                at = i;
                break;
            }
        }
        assertTrue(at > 0, "a snapshot holding the first question");
        long seq = CODEC.mapper().readTree(text.get(at)).get("snapshot").asLong();
        text.set(at, text.get(at).replaceFirst("\"first ", "\"forged "));
        Files.write(file, text);

        assertEquals(java.util.OptionalLong.of(seq), replay(snapshotted).firstWrongSnapshot(principal, session));
    }

    /** 什么时候要下一份：写过的字节超过门槛、且不少于两倍上一份快照；取一次旗就放下，连着几步只做一份。 */
    @Test
    void theNextSnapshotIsWantedOnlyAfterEnoughHasBeenWritten() throws Exception {
        Principal principal = ReplayScenario.ALICE;
        SessionId session = ReplayScenario.COMPACTED;
        List<Line> lines = lines(original, principal, session);
        SessionPaths target = new SessionPaths(temp.resolve("wanted"));
        try (SessionFiles files = new SessionFiles(target, CODEC, 4, (p, s, seq, offset) -> { }).snapshotBytes(1)) {
            files.append(principal, session, List.of(new Line(lines.get(0).envelope(), lines.get(0).record())), null);
            assertTrue(files.takeWanted(principal, session), "past the threshold");
            assertTrue(!files.takeWanted(principal, session), "taken once");
            KernelState state = lines.get(0).record().apply(lines.get(0).envelope(), KernelState.empty()).newState();
            Projections projections = new Projections(new Transcript(), new Trace());
            Snapshot snapshot = SNAPSHOTS.of(projections.export(state, session, lines.get(0).seq()));
            files.append(principal, session, List.of(new Line(lines.get(1).envelope(), lines.get(1).record())), null);
            assertTrue(files.takeWanted(principal, session));
            // 写了快照以后：要再写两倍于它的字节才再要
            files.append(principal, session, List.of(new Line(lines.get(2).envelope(), lines.get(2).record())), snapshot.seq() < 0 ? null
                    : SNAPSHOTS.of(new Projections(new Transcript(), new Trace()).export(applied(lines, 3), session, lines.get(2).seq())));
            assertTrue(!files.takeWanted(principal, session), "right after a snapshot nothing is wanted");
        }
    }

    private static KernelState applied(List<Line> lines, int count) {
        KernelState state = KernelState.empty();
        for (Line line : lines.subList(0, count)) {
            state = line.record().apply(line.envelope(), state).newState();
        }
        return state;
    }
}
