package tantalum.shell.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.FailureReason;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.Compacting;
import tantalum.value.state.EndReason;

/**
 * 对话里的一个条目，是事件的展示投影。ID 由事件序号和块下标算出，回放时相同；
 * 同一 ID 的新版本覆盖旧版本，客户端据此原地更新。{@code kind} 是接口里的类型名，只在这里定义。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TranscriptItem.UserMessage.class, name = "user"),
    @JsonSubTypes.Type(value = TranscriptItem.Text.class, name = "text"),
    @JsonSubTypes.Type(value = TranscriptItem.Thinking.class, name = "thinking"),
    @JsonSubTypes.Type(value = TranscriptItem.ToolStep.class, name = "tool"),
    @JsonSubTypes.Type(value = TranscriptItem.RunStatus.class, name = "run"),
    @JsonSubTypes.Type(value = TranscriptItem.CompactionMark.class, name = "compaction"),
    @JsonSubTypes.Type(value = TranscriptItem.ToolChange.class, name = "toolChange")
})
public sealed interface TranscriptItem {

    String id();

    SessionId session();

    /** 所属运行。排队中的提交还没有运行，为空。 */
    RunId run();

    /** 产生该条目的事件时间。 */
    Instant at();

    /** 由事件序号和块下标算出的 ID。 */
    static String id(long seq, int index) {
        return seq + "." + index;
    }

    /**
     * 用户的提交。运行中提交的 {@code follow-up} 先排队，运行开始后 {@code queued} 变为 false。
     * {@code attachments} 按上传顺序；没有时不写出，接口和推送的格式和以前相同。
     */
    record UserMessage(String id, SessionId session, RunId run, Instant at, String text,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AttachmentRef> attachments, boolean queued)
            implements TranscriptItem {

        public UserMessage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(text, "text");
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public UserMessage(String id, SessionId session, RunId run, Instant at, String text, boolean queued) {
            this(id, session, run, at, text, List.of(), queued);
        }

        public UserMessage started(RunId startedRun) {
            return new UserMessage(id, session, startedRun, at, text, attachments, false);
        }
    }

    /** 回复文本。{@code interrupted} 表示回复被取消，只有用户已经看到的部分；{@code truncated} 表示回复因输出上限被截断。 */
    record Text(String id, SessionId session, RunId run, Instant at, String text, boolean interrupted, boolean truncated)
            implements TranscriptItem {

        public Text {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(text, "text");
        }
    }

    /** 模型的思考。供应商不给出文本时（如 Claude 的 {@code redacted_thinking}）{@code redacted} 为 true，文本为空。 */
    record Thinking(String id, SessionId session, RunId run, Instant at, String text, boolean redacted)
            implements TranscriptItem {

        public Thinking {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(text, "text");
        }
    }

    /** 工具步骤的状态。{@code BLOCKED} 是被钩子拦截或参数无效，没有执行。 */
    enum StepStatus {
        WAITING,
        RUNNING,
        DONE,
        FAILED,
        BLOCKED
    }

    /**
     * 一次工具调用。同一事件发出的调用 {@code group} 相同，是一组并行调用。起止时间取事件时间，
     * 排队等服务端名额的时间也算在里面，因为 journal 不记录真正开始执行的时刻。
     */
    record ToolStep(String id, SessionId session, RunId run, Instant at, ToolCallBlock call, StepStatus status,
                    String group, Instant startedAt, Instant endedAt, ToolResultBlock result)
            implements TranscriptItem {

        public ToolStep {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(status, "status");
        }

        public static ToolStep waiting(String id, SessionId session, RunId run, Instant at, ToolCallBlock call) {
            return new ToolStep(id, session, run, at, call, StepStatus.WAITING, null, null, null, null);
        }

        public ToolStep running(String newGroup, Instant start) {
            return new ToolStep(id, session, run, at, call, StepStatus.RUNNING, newGroup, start, null, null);
        }

        /** 真实结果或合成结果。错误结果记为 {@code FAILED}。 */
        public ToolStep finished(ToolResultBlock toolResult, Instant end) {
            StepStatus next = toolResult.error() ? StepStatus.FAILED : StepStatus.DONE;
            return new ToolStep(id, session, run, at, call, next, group, startedAt, end, toolResult);
        }

        /** 没有执行就有了错误结果：被拦截或参数无效。 */
        public ToolStep blocked(ToolResultBlock toolResult, Instant when) {
            return new ToolStep(id, session, run, at, call, StepStatus.BLOCKED, group, when, when, toolResult);
        }

        public boolean isFinished() {
            return status == StepStatus.DONE || status == StepStatus.FAILED || status == StepStatus.BLOCKED;
        }

        /** 起止都已知时的耗时，否则为空。 */
        public Duration elapsed() {
            return startedAt == null || endedAt == null ? null : Duration.between(startedAt, endedAt);
        }
    }

    /**
     * 一次压缩。成功时 {@code summary} 是摘要原文、{@code failure} 为空；失败时反过来。估算是压缩前后视图的 token 估算，
     * {@code usage} 是摘要调用的实测用量，可能为空。对话页在这里画分隔线。
     */
    record CompactionMark(String id, SessionId session, RunId run, Instant at, Compacting.Trigger trigger, String summary,
                          long estimateBefore, long estimateAfter, TokenUsage usage, String failure)
            implements TranscriptItem {

        public CompactionMark {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(trigger, "trigger");
            if ((summary == null) == (failure == null)) {
                throw new IllegalArgumentException("exactly one of summary and failure must be set");
            }
        }

        public boolean failed() {
            return failure != null;
        }
    }

    /**
     * 会话中途工具变了（one-line-journal J8）：从这里起新增了哪些工具、哪些不能用了。不能用的分两种：{@code disabled} 是
     * 服务器上还有、用户在插件页关了（本机工作区的工具也可能是电脑没连上或没允许）；{@code gone} 是服务器上已经没有
     * （插件删了或没加载、MCP 服务器不在了）。{@code sources} 是这几个工具各自的来源（{@code ContextAssembled.toolSources}），
     * 画面据此写原因，如来源为 {@code local} 时写“本机工作区没有连上或没有允许”（local-workspace）。
     */
    record ToolChange(String id, SessionId session, RunId run, Instant at, List<String> added, List<String> disabled,
                      List<String> gone, Map<String, String> sources) implements TranscriptItem {

        public ToolChange {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(at, "at");
            added = List.copyOf(added);
            disabled = List.copyOf(disabled);
            gone = List.copyOf(gone);
            sources = sources == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(sources));
        }
    }

    /** 运行的状态，一个运行一条。{@code phase} 是内核状态名；结束后带原因；模型失败时带最近一次失败原因。 */
    record RunStatus(String id, SessionId session, RunId run, Instant at, Instant updatedAt, String phase,
                     EndReason endReason, String detail, FailureReason failure)
            implements TranscriptItem {

        public RunStatus {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(phase, "phase");
        }

        public static String idOf(RunId run) {
            return "run:" + run.value();
        }

        public static RunStatus started(SessionId session, RunId run, Instant at, String phase) {
            return new RunStatus(idOf(run), session, run, at, at, phase, null, null, null);
        }

        public RunStatus inPhase(String newPhase, Instant when) {
            return new RunStatus(id, session, run, at, when, newPhase, endReason, detail, failure);
        }

        public RunStatus ended(EndReason reason, String endDetail, Instant when) {
            return new RunStatus(id, session, run, at, when, "Ended", reason, endDetail, failure);
        }

        public RunStatus failedWith(FailureReason reason, Instant when) {
            return new RunStatus(id, session, run, at, when, phase, endReason, detail, reason);
        }

        public boolean isEnded() {
            return endReason != null;
        }

        public Duration elapsed() {
            return Duration.between(at, updatedAt);
        }
    }
}
