package tantalum.shell.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.loop.Projections;
import tantalum.shell.sse.SseHub;
import tantalum.shell.sse.SseMessage;
import tantalum.shell.trace.Trace;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.decision.Fact;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.model.Prompt;
import tools.jackson.databind.json.JsonMapper;

/**
 * 重放会话文件回答历史问题。{@code /turns} 只读运行所在的那一个会话文件；SSE 补读只读该用户
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

    /** 一条补读消息和它的位置，用来把各会话的结果按（序号，下标）合并。 */
    private record Positioned(long seq, int index, SseMessage message) {}

    private final SessionPaths paths;
    private final RecordReplay replay;
    private final JsonMapper mapper;

    /** 按结果行折叠，不调内核（record-results 第 2 步）。还没写结果行的最后一步不出现，它也还没推送过。 */
    public TurnQuery(SessionPaths paths, RecordReplay replay, JsonMapper mapper) {
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
        } catch (IOException failure) {
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

    /**
     * SSE 补读：某用户在某位置之后的事实、条目和轨迹记录的变化，编号规则与 {@code Cleanroom} 相同：每个事件先事实，再条目，再轨迹记录。
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
            Projections projections = new Projections(new Transcript(), new Trace());
            each(principal, file, (envelope, before, decision) -> {
                int index = 0;
                for (Fact fact : decision.facts()) {
                    if (later(envelope.seq(), index, lastSeq, lastIndex)) {
                        messages.add(new Positioned(envelope.seq(), index, SseHub.factMessage(mapper, envelope.seq(), index, fact)));
                    }
                    index++;
                }
                Projections.Output projected = projections.apply(envelope, before, decision);
                for (TranscriptItem item : projected.items()) {
                    if (later(envelope.seq(), index, lastSeq, lastIndex)) {
                        messages.add(new Positioned(envelope.seq(), index, SseHub.itemMessage(mapper, envelope.seq(), index, item)));
                    }
                    index++;
                }
                for (TraceRecord record : projected.records()) {
                    if (later(envelope.seq(), index, lastSeq, lastIndex)) {
                        messages.add(new Positioned(envelope.seq(), index, SseHub.recordMessage(mapper, envelope.seq(), index, record)));
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
