package tantalum.shell.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.shell.journal.RecordCodec;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.store.SessionQueries.SessionUnreadable;
import tantalum.shell.trace.Trace;
import tantalum.shell.trace.TraceRecord;
import tantalum.shell.trace.TraceRecord.Segment;
import tantalum.shell.trace.TraceRecord.Source;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ToolSpec;

/**
 * 轨迹接口的数据：每次请求重放一次会话文件，常驻和冷会话一样，不读内存状态，所以和实时推送出自同一个投影。
 * 只读本人目录下的文件，别人的会话和不存在一样。
 */
public final class TraceQuery {

    /** 一个工具和它的来源。 */
    public record SourcedTool(ToolSpec spec, Source source) {}

    /** 上一次请求的 system 和工具，“差异”页签用。 */
    public record Previous(String system, List<ToolSpec> tools) {}

    /**
     * 一次尝试的完整输入：有效 system 和分段来源、工具、消息、输出约束，以及上一次请求的 system 和工具（第一次请求时为空）。
     * {@code fingerprintMismatch} 为 true 时按结果行还原的请求和当时记下的指纹不一致，false 不写出。
     */
    public record Call(String call, String system, List<Segment> segments, List<SourcedTool> tools, List<Message> messages,
                       Prompt.Output output, Previous previous,
                       @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean fingerprintMismatch,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Addition> additions) {}

    /** 这次请求的消息里钩子追加的一段说明。 */
    public record Addition(String hook, String text) {}

    /** 命令 ID 是 {@code <会话>_<序号>-<下标>}（{@code CommandIds}）。 */
    private static final Pattern COMMAND_TAIL = Pattern.compile("(\\d{1,18})-\\d{1,9}");

    private final SessionPaths paths;
    private final RecordReplay replay;
    private final RecordCodec codec;

    /** 按结果行折叠，不调内核。{@code codec} 用来核对请求指纹。 */
    public TraceQuery(SessionPaths paths, RecordReplay replay, RecordCodec codec) {
        this.paths = paths;
        this.replay = replay;
        this.codec = codec;
    }

    /** 重放整个会话的结果：每条记录的最终版本和重放到的序号。 */
    private record Replayed(long seq, Map<String, TraceRecord> records) {}

    /** {@code before} 为空时取最新一页。会话不存在或不是本人的为空。 */
    public Optional<TracePage> page(Principal principal, SessionId session, Long before) {
        return replayed(principal, session).map(all -> TracePage.of(all.seq(), all.records().values(), before));
    }

    /** 从最新一直到包含 {@code run} 的一页；会话或运行不存在时为空。 */
    public Optional<TracePage> through(Principal principal, SessionId session, RunId run) {
        return replayed(principal, session).map(all -> TracePage.through(all.seq(), all.records().values(), run));
    }

    private Optional<Replayed> replayed(Principal principal, SessionId session) {
        Path file = paths.sessionFile(principal, session);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Trace trace = new Trace();
        Map<String, TraceRecord> records = new LinkedHashMap<>();
        long[] seq = {-1};
        read(session, () -> replay.each(principal, file, (envelope, before, decision) -> {
            trace.apply(envelope, before, decision).forEach(record -> records.put(record.id(), record));
            seq[0] = envelope.seq();
        }));
        return Optional.of(new Replayed(seq[0], records));
    }

    /** 一次尝试的完整输入，只重放到发出它的事件为止。命令不属于这个会话或不存在时为空。 */
    public Optional<Call> call(Principal principal, SessionId session, CommandId command) {
        String prefix = session.value() + "_";
        Matcher tail = command.value().startsWith(prefix) ? COMMAND_TAIL.matcher(command.value().substring(prefix.length())) : null;
        Path file = paths.sessionFile(principal, session);
        if (tail == null || !tail.matches() || !Files.exists(file)) {
            return Optional.empty();
        }
        long seq = Long.parseLong(tail.group(1));
        Trace trace = new Trace();
        List<Prompt> previous = new ArrayList<>();
        List<Call> found = new ArrayList<>();
        read(session, () -> replay.until(principal, file, seq + 1, (RecordReplay.RecordedStep) (envelope, before, decision, fingerprints) -> {
            trace.apply(envelope, before, decision);
            for (Command sent : decision.commands()) {
                if (!(sent instanceof CallModel call) || !call.run().session().equals(session)) {
                    continue;
                }
                if (call.id().equals(command)) {
                    boolean mismatch = !codec.fingerprint(call.prompt()).equals(fingerprints.get(call.id()));
                    found.add(describe(call, trace.lastSplit(session, call.run().run()), previous.isEmpty() ? null : previous.getLast(), mismatch));
                } else {
                    previous.add(call.prompt());
                }
            }
        }));
        return found.stream().findFirst();
    }

    private static Call describe(CallModel call, Trace.Split split, Prompt previous, boolean mismatch) {
        Prompt prompt = call.prompt();
        List<SourcedTool> tools = new ArrayList<>();
        for (int i = 0; i < prompt.tools().size(); i++) {
            Source source = split != null && i < split.tools().size() ? split.tools().get(i) : Source.UNKNOWN;
            tools.add(new SourcedTool(prompt.tools().get(i), source));
        }
        List<Segment> segments = split == null ? List.of(new Segment(prompt.system().length(), Source.UNKNOWN)) : split.system();
        List<Addition> additions = prompt.messages().stream().flatMap(message -> message.content().stream())
                .filter(HookTextBlock.class::isInstance).map(HookTextBlock.class::cast)
                .map(note -> new Addition(note.hook(), note.text())).toList();
        return new Call(call.id().value(), prompt.system(), segments, tools, prompt.messages(), prompt.output(),
                previous == null ? null : new Previous(previous.system(), previous.tools()), mismatch, additions);
    }

    @FunctionalInterface
    private interface Reading {
        void run() throws Exception;
    }

    /** 会话文件读不了（中间损坏、索引库不可用）时抛 {@link SessionUnreadable}，接口返回 500。 */
    private static void read(SessionId session, Reading reading) {
        try {
            reading.run();
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new SessionUnreadable(session, stopping);
        } catch (Exception failure) {
            throw new SessionUnreadable(session, failure);
        }
    }
}
