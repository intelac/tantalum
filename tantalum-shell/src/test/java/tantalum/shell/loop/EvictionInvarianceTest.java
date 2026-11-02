package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.store.IndexWriter;
import tantalum.shell.store.SessionFileReader;
import tantalum.shell.store.SessionFiles;
import tantalum.shell.store.SessionIndex;
import tantalum.shell.store.SessionPaths;
import tantalum.shell.store.SessionReplay;
import tantalum.shell.store.SnapshotStore;
import tantalum.shell.store.SnapshotWriter;
import tantalum.shell.store.UsageLedger;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.RunTool;
import tantalum.value.decision.Fact;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;

/**
 * 任务 29（需求 9.1）：同一组输入在“从不回收”“每个事件后回收”“每次从快照载入”下，
 * 内核发出的命令（含给模型的完整 prompt）、事实（含钩子结果）和对话条目逐字节相同。走真实的环、通道、文件和索引。
 */
class EvictionInvarianceTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId TOOLS = new SessionId("tools");
    private static final SessionId COMPACTED = new SessionId("compacted");
    private static final SessionId STOPPED = new SessionId("stopped");
    private static final SessionId BOBS = new SessionId("bobs");
    private static final CompactionSettings SETTINGS = new CompactionSettings(1000, 200, 70, 60, 30, 500);
    private static final Instant AT = Instant.parse("2026-11-02T01:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final long QUOTA = 3000;

    /** 读该用户所有会话的累计用量，回收后重放时必须看到和当时相同的值。 */
    private static final KernelHook QUOTA_HOOK = new KernelHook() {
        @Override public String name() { return "quota"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            var principal = state.principals().get(context.run().principal());
            if (principal == null) {
                return Allow.PLAIN;
            }
            TokenUsage today = principal.usageOn(LocalDate.ofInstant(context.at(), ZoneOffset.UTC));
            return today.promptTotal() + today.output() >= QUOTA ? new Stop("quota") : Allow.PLAIN;
        }
    };

    private enum Mode { NEVER, EVERY_EVENT, FROM_SNAPSHOT }

    @TempDir
    Path temp;

    /** 一套完整的管线。命令不执行，记下来由场景按脚本回复，所以三种配置的输入完全相同。 */
    private static final class Pipeline implements AutoCloseable {
        final List<String> log = new ArrayList<>();
        final List<Integer> loads = new ArrayList<>();
        final Deque<Command> pending = new ArrayDeque<>();
        final StateView view = new StateView(KernelState.empty());
        /** 场景结束时每个会话完整的对话视图：在内存的直接导出，不在的按线上冷查询的方式读（有快照就用快照）。 */
        final Map<SessionId, Transcript.Part> views = new java.util.TreeMap<>(java.util.Comparator.comparing(SessionId::value));
        private final Transcript transcript;
        private final SessionReplay replay;
        private final SessionIndex index;
        private final IndexWriter writer;
        private final SessionFiles files;
        private final SnapshotWriter snapshots;
        private final InputRing input;
        private final CleanroomRing ring;
        private final JournalDispatcher dispatcher;
        private final Mode mode;

        Pipeline(Path dir, Mode mode) throws Exception {
            this.mode = mode;
            TransitionTable table = new TransitionTable();
            BuiltinSteps.register(table);
            Kernel kernel = new Kernel(table, new HookChain(List.of(QUOTA_HOOK)), (tool, args) -> tool.startsWith("safe"), 10);
            EventCodec codec = EventCodec.withBuiltins();
            SessionPaths paths = new SessionPaths(dir);
            index = SessionIndex.open(dir);
            writer = new IndexWriter(index, 100, Duration.ofHours(1));
            files = new SessionFiles(paths, codec, 16, writer::onDurable);
            snapshots = new SnapshotWriter(new SnapshotStore(paths, codec), paths);
            SessionReplay replay = new SessionReplay(paths, new SessionFileReader(codec), new SnapshotStore(paths, codec), new UsageLedger(index, writer), kernel);
            this.replay = replay;
            Residency residency = new Residency();
            transcript = new Transcript();
            Evictor evictor = mode == Mode.NEVER ? new Evictor(LoopFixtures.NEVER, residency, transcript, LoopFixtures.NO_SINK, Clock.systemUTC())
                    // 快照配置下运行结束也写快照（门槛 1 个事件），回收时快照已是最新，载入用的就是运行结束时写的那份。
                    : new Evictor(new Evictor.Settings(Duration.ZERO, Integer.MAX_VALUE, mode == Mode.FROM_SNAPSHOT ? 1 : Integer.MAX_VALUE, Duration.ZERO),
                            residency, transcript,
                            mode == Mode.FROM_SNAPSHOT ? snapshots : LoopFixtures.NO_SINK, Clock.systemUTC());

            input = new InputRing(64);
            ring = new CleanroomRing(64);
            dispatcher = new JournalDispatcher(64, new TailSequence(paths, 1), files::append, ring::tryPublish,
                    LoopFixtures.loader((principal, session, beforeSeq) -> load(replay, principal, session, beforeSeq)), residency);
            FactListener facts = new FactListener() {
                @Override
                public void onFact(long seq, int index, Fact fact) {
                    log.add("fact " + seq + "." + index + " " + fact);
                }

                @Override
                public void onItem(long seq, int index, Principal owner, TranscriptItem item) {
                    log.add("item " + seq + "." + index + " " + item);
                }
            };
            Cleanroom cleanroom = new Cleanroom(kernel, KernelState.empty(), dispatcher::halted, command -> {
                log.add("command " + command);
                pending.add(command);
                return true;
            }, transcript, facts, view, new ResultPublisher(input, new InFlightCommands(), Clock.systemUTC(), 8), writer::onStep, evictor);
            input.connect(dispatcher);
            ring.connect((slot, sequence, endOfBatch) -> {
                cleanroom.onEvent(slot, sequence, endOfBatch);
                dispatcher.released();
            });
            ring.start();
            input.start();
        }

        private Optional<Preload> load(SessionReplay replay, Principal principal, SessionId session, long beforeSeq) throws Exception {
            return replay.load(principal, session, beforeSeq).map(slice -> {
                loads.add(slice.replayedEvents());
                return Preload.loaded(slice.snapshot().session(), slice.snapshot().runs(), slice.snapshot().transcript(),
                        slice.snapshot().seq(), slice.replayedEvents());
            });
        }

        /** 一个事件走完整条管线才返回；快照配置下还等快照写完，下一次载入一定用得上它。 */
        void send(Principal principal, SessionId session, RunId run, Event event) throws Exception {
            assertTrue(input.tryPublish(new EventEnvelope(EventEnvelope.UNASSIGNED, AT, principal, session, run, event)));
            assertTrue(input.awaitDrained(WAIT));
            assertTrue(dispatcher.awaitIdle(WAIT));
            if (mode == Mode.FROM_SNAPSHOT) {
                snapshots.flush();
            }
        }

        void captureViews(Map<SessionId, Principal> sessions) throws Exception {
            snapshots.flush();
            for (var entry : sessions.entrySet()) {
                SessionId session = entry.getKey();
                views.put(session, transcript.sessions().contains(session) ? transcript.export(session)
                        : replay.load(entry.getValue(), session, Long.MAX_VALUE).orElseThrow().snapshot().transcript());
            }
        }

        @Override
        public void close() throws Exception {
            input.shutdown(WAIT);
            ring.shutdown(WAIT);
            snapshots.close();
            files.close();
            writer.close();
            index.close();
        }
    }

    /** 前半段和 {@code SessionReplayTest} 相同，最后每个会话再来一轮，让回收过的会话重新载入：工具轮加 thinking、压缩、每次组装的记忆都不同、另一个用户交错、额度用完。 */
    private static final class Scenario {
        private final Pipeline pipeline;
        private final Deque<List<ContentBlock>> scripted = new ArrayDeque<>();
        private int requests;
        private int dynamic;

        Scenario(Pipeline pipeline) {
            this.pipeline = pipeline;
        }

        void prompt(Principal principal, SessionId session, String text) throws Exception {
            String request = "req-" + requests++;
            pipeline.send(principal, session, null,
                    new UserPrompt(new SubmissionId("sub-" + request), new RequestId(request), new OwnerRef.OfSession(session), text));
        }

        private static ToolCallBlock call(String id) {
            return new ToolCallBlock(id, "safe_read", "{\"p\":1}", new Json.Obj(Map.of("p", new Json.Num(java.math.BigDecimal.ONE))));
        }

        void drain() throws Exception {
            while (!pipeline.pending.isEmpty()) {
                Command next = pipeline.pending.poll();
                Principal principal = next.run().principal();
                SessionId session = next.run().session();
                RunId run = next.run().run();
                switch (next) {
                    case AssembleContext assemble -> pipeline.send(principal, session, run,
                            new ContextAssembled(assemble.id(), "system", List.of(), "memory-" + dynamic++, SETTINGS));
                    case CallModel model -> {
                        boolean summary = !model.prompt().output().toolsAllowed();
                        List<ContentBlock> content = summary ? List.of(new TextBlock("the summary of everything so far"))
                                : scripted.isEmpty() ? List.of(new TextBlock("done " + "x".repeat(90))) : scripted.poll();
                        StopReason stop = content.stream().anyMatch(ToolCallBlock.class::isInstance) ? StopReason.TOOL_USE : StopReason.END_TURN;
                        pipeline.send(principal, session, run, new ModelReplied(model.id(), content, stop, "m", new TokenUsage(summary ? 50 : 790, 10, 0, 0)));
                    }
                    case RunTool tool -> pipeline.send(principal, session, run,
                            new ToolFinished(tool.id(), tool.call().id(), "result of " + tool.call().id(), false));
                    default -> { }
                }
            }
        }

        void run() throws Exception {
            scripted.add(List.of(new ReasoningBlock("claude", new Json.Str("hmm")), new TextBlock("calling"), call("t1"), call("t2")));
            prompt(ALICE, TOOLS, "read two files " + "q".repeat(90));
            prompt(BOB, BOBS, "bob asks " + "q".repeat(90));
            prompt(ALICE, COMPACTED, "first " + "q".repeat(90));
            drain();
            prompt(ALICE, COMPACTED, "second, over the threshold " + "q".repeat(90));
            prompt(BOB, BOBS, "bob again");
            drain();
            prompt(ALICE, TOOLS, "third");
            drain();
            prompt(ALICE, STOPPED, "too late, the quota is used up");
            drain();
            // 每个会话再来一轮：回收过的会话都要重新载入，重放它们的历史，包括被额度钩子停下的那次。
            prompt(ALICE, STOPPED, "again");
            prompt(ALICE, COMPACTED, "after the quota");
            prompt(ALICE, TOOLS, "and here");
            prompt(BOB, BOBS, "bob is not limited by alice's quota");
            drain();
        }
    }

    private Pipeline run(Mode mode) throws Exception {
        Path dir = java.nio.file.Files.createDirectories(temp.resolve(mode.name()));
        Pipeline pipeline = new Pipeline(dir, mode);
        try {
            new Scenario(pipeline).run();
            pipeline.captureViews(Map.of(TOOLS, ALICE, COMPACTED, ALICE, STOPPED, ALICE, BOBS, BOB));
        } finally {
            pipeline.close();
        }
        return pipeline;
    }

    @Test
    void theScenarioReallyCoversWhatItClaims() throws Exception {
        Pipeline never = run(Mode.NEVER);
        KernelState state = never.view.current();
        assertNotNull(state.sessions().get(COMPACTED).compaction(), "compaction happened");
        assertTrue(state.sessions().get(TOOLS).messages().toString().contains("result of t2"), "tool round happened");
        assertTrue(never.log.stream().anyMatch(line -> line.contains("memory-") && line.startsWith("command CallModel")), "memory reaches the prompt");
        RunState stopped = state.runs().values().stream().filter(run -> run.ref().session().equals(STOPPED)).findFirst().orElseThrow();
        assertEquals(EndReason.STOPPED, ((Ended) stopped.phase()).reason(), "the quota hook needs usage from other sessions");
        assertEquals(List.of(), never.loads, "nothing was ever evicted");
    }

    @Test
    void evictingAfterEveryEventChangesNothing() throws Exception {
        Pipeline never = run(Mode.NEVER);
        Pipeline every = run(Mode.EVERY_EVENT);
        assertFalse(every.loads.isEmpty());
        assertTrue(every.loads.stream().allMatch(replayed -> replayed > 0), "no snapshots in this configuration: " + every.loads);
        assertEquals(never.log, every.log);
        assertEquals(never.views, every.views);
        assertTrue(every.view.current().sessions().isEmpty(), "everything idle has left memory");
    }

    @Test
    void loadingFromASnapshotEveryTimeChangesNothing() throws Exception {
        Pipeline never = run(Mode.NEVER);
        Pipeline snapshot = run(Mode.FROM_SNAPSHOT);
        assertFalse(snapshot.loads.isEmpty());
        assertTrue(snapshot.loads.stream().allMatch(replayed -> replayed == 0), "every load came from a snapshot: " + snapshot.loads);
        assertEquals(never.log, snapshot.log);
        assertEquals(never.views, snapshot.views);
    }
}
