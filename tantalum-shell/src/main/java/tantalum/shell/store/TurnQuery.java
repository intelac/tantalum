package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.sse.SseHub;
import tantalum.shell.sse.SseMessage;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.decision.CompactionFailed;
import tantalum.value.decision.ContextCompacted;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.model.Prompt;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.RunState;
import tools.jackson.databind.json.JsonMapper;

/**
 * 重放会话文件回答历史问题。{@code /turns}、{@code /trace} 只读运行所在的那一个会话文件；SSE 补读只读该用户
 * 末尾序号比位置新的文件。路径由用户名决定，所以别人的运行读不到，和不存在一样。
 */
public final class TurnQuery {

    /** 一次模型调用：完整输入和结果。 */
    public record Turn(int number, CommandId command, Prompt prompt, ModelReplied reply, ModelFailed failure) {

        Turn withReply(ModelReplied modelReply) {
            return new Turn(number, command, prompt, modelReply, failure);
        }

        Turn withFailure(ModelFailed modelFailure) {
            return new Turn(number, command, prompt, reply, modelFailure);
        }
    }

    /** 一次运行的轨迹：事件列表和两事件之间由哪个 Handler 在干活的时间段。 */
    public record Trace(List<Entry> events, List<Segment> segments) {

        /** {@code phase} 是事件处理后的状态名；{@code turn} 是这个事件发出的模型调用序号，没有则为空；
         * {@code compaction} 是这个事件带来的压缩结果，没有则为空。 */
        public record Entry(long seq, Instant at, String type, String phase, Integer turn, Compaction compaction) {}

        /** 一次压缩的结果：成功时带前后估算和摘要调用用量，失败时带原因。 */
        public record Compaction(String trigger, boolean ok, long estimateBefore, long estimateAfter, TokenUsage usage, String reason) {}

        /** 还在等待时 {@code to} 为空。 */
        public record Segment(String handler, Instant from, Instant to) {}
    }

    /** 一条补读消息和它的位置，用来把各会话的结果按（序号，下标）合并。 */
    private record Positioned(long seq, int index, SseMessage message) {}

    private final SessionPaths paths;
    private final SessionReplay replay;
    private final JsonMapper mapper;

    public TurnQuery(SessionPaths paths, SessionReplay replay, JsonMapper mapper) {
        this.paths = paths;
        this.replay = replay;
        this.mapper = mapper;
    }

    /** 运行所在会话的全部事件，读不了时抛 {@link IllegalStateException}。 */
    private void eachOfRun(Principal principal, RunId run, SessionReplay.Step step) {
        if (run.session().isEmpty()) {
            return;
        }
        each(principal, paths.sessionFile(principal, run.session().get()), step);
    }

    private void each(Principal principal, Path file, SessionReplay.Step step) {
        try {
            replay.each(principal, file, step);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading " + file, stopping);
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("cannot read " + file, failure);
        }
    }

    /** 不属于该用户的运行返回空，和不存在一样。 */
    public List<Turn> turns(Principal principal, RunId run) {
        Map<CommandId, Turn> turns = new LinkedHashMap<>();
        eachOfRun(principal, run, (envelope, before, decision) -> {
            if (envelope.event() instanceof ModelReplied reply && turns.containsKey(reply.command())) {
                turns.compute(reply.command(), (id, turn) -> turn.withReply(reply));
            } else if (envelope.event() instanceof ModelFailed failure && turns.containsKey(failure.command())) {
                turns.compute(failure.command(), (id, turn) -> turn.withFailure(failure));
            }
            for (Command command : decision.commands()) {
                if (command instanceof CallModel call && call.run().run().equals(run)) {
                    turns.put(call.id(), new Turn(turns.size() + 1, call.id(), call.prompt(), null, null));
                }
            }
        });
        return new ArrayList<>(turns.values());
    }

    /** 运行的轨迹。不属于该用户或不存在时事件为空。 */
    public Trace trace(Principal principal, RunId run) {
        List<Trace.Entry> entries = new ArrayList<>();
        int[] turns = {0};
        eachOfRun(principal, run, (envelope, before, decision) -> {
            boolean existed = before.runs().containsKey(run);
            RunState current = decision.newState().runs().get(run);
            boolean belongs = envelope.run() != null ? envelope.run().equals(run) : !existed && current != null;
            if (!belongs || current == null) {
                return;
            }
            Integer turn = null;
            for (Command command : decision.commands()) {
                if (command instanceof CallModel call && call.run().run().equals(run)) {
                    turn = ++turns[0];
                }
            }
            entries.add(new Trace.Entry(envelope.seq(), envelope.at(), envelope.event().type(), current.phase().name(), turn,
                    compactionOf(decision, run)));
        });
        List<Trace.Segment> segments = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Trace.Entry entry = entries.get(i);
            if (entry.phase().equals("Ended")) {
                break;
            }
            Instant to = i + 1 < entries.size() ? entries.get(i + 1).at() : null;
            segments.add(new Trace.Segment(handlerOf(entry.phase()), entry.at(), to));
        }
        return new Trace(entries, segments);
    }

    private static Trace.Compaction compactionOf(Decision decision, RunId run) {
        for (Fact fact : decision.facts()) {
            if (fact instanceof ContextCompacted ok && ok.run().run().equals(run)) {
                return new Trace.Compaction(ok.trigger().name(), true, ok.estimateBefore(), ok.estimateAfter(), ok.usage(), null);
            }
            if (fact instanceof CompactionFailed failed && failed.run().run().equals(run)) {
                return new Trace.Compaction(failed.trigger().name(), false, 0, 0, null, failed.reason());
            }
        }
        return null;
    }

    /** 等待状态名到负责它的 Handler。 */
    static String handlerOf(String phase) {
        return switch (phase) {
            case "AwaitingContext" -> "ContextHandler";
            case "AwaitingModel", "AwaitingSummary" -> "ModelHandler";
            case "AwaitingTools" -> "ToolHandler";
            case "AwaitingWork" -> "WorkHandler";
            case "AwaitingRetry", "AwaitingSummaryRetry" -> "TimerHandler";
            default -> phase;
        };
    }

    /**
     * SSE 补读：某用户在某位置之后的事实和条目变化，编号规则与 {@code Cleanroom} 相同：每个事件先事实再条目。
     * 只完整重放末尾序号不早于位置的文件，其余文件只读末尾一行；不依赖可能落后的索引。
     */
    public List<SseMessage> messagesAfter(Principal principal, String lastEventId) {
        String[] parts = lastEventId.split("\\.");
        long lastSeq = Long.parseLong(parts[0]);
        int lastIndex = Integer.parseInt(parts[1]);
        List<Positioned> messages = new ArrayList<>();
        for (Path file : files(principal)) {
            try {
                if (SessionFileReader.lastSeq(file) < lastSeq) {
                    continue;
                }
            } catch (IOException failure) {
                throw new IllegalStateException("cannot read " + file, failure);
            }
            Transcript transcript = new Transcript();
            each(principal, file, (envelope, before, decision) -> {
                int index = 0;
                for (Fact fact : decision.facts()) {
                    if (later(envelope.seq(), index, lastSeq, lastIndex)) {
                        messages.add(new Positioned(envelope.seq(), index, SseHub.factMessage(mapper, envelope.seq(), index, fact)));
                    }
                    index++;
                }
                for (TranscriptItem item : transcript.apply(envelope, decision)) {
                    if (later(envelope.seq(), index, lastSeq, lastIndex)) {
                        messages.add(new Positioned(envelope.seq(), index, SseHub.itemMessage(mapper, envelope.seq(), index, item)));
                    }
                    index++;
                }
            });
        }
        messages.sort(Comparator.comparingLong(Positioned::seq).thenComparingInt(Positioned::index));
        return messages.stream().map(Positioned::message).toList();
    }

    private List<Path> files(Principal principal) {
        Path dir = paths.userDir(principal);
        try {
            return paths.sessionFiles(dir);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot list " + dir, failure);
        }
    }

    private static boolean later(long seq, int index, long lastSeq, int lastIndex) {
        return seq > lastSeq || (seq == lastSeq && index > lastIndex);
    }
}
