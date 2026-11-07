package tantalum.shell.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.decision.HookDecided;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.RunId;
import tantalum.value.json.Json;
import tantalum.value.model.FailureReason;
import tantalum.value.model.RequestOptions;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.Compacting;
import tantalum.value.state.EndReason;

/**
 * 轨迹的一条记录，是事件的展示投影。ID 由序号或命令 ID 算出，回放时相同；同一 ID 的新版本覆盖旧版本。
 * 共有的字段放在 {@link Head}，接口里摊平（{@link TraceJson}）。{@code kind} 是接口里的类型名，只在这里定义。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TraceRecord.RunHead.class, name = "run"),
    @JsonSubTypes.Type(value = TraceRecord.SystemPrompt.class, name = "system"),
    @JsonSubTypes.Type(value = TraceRecord.UserInput.class, name = "user"),
    @JsonSubTypes.Type(value = TraceRecord.ContextChange.class, name = "context"),
    @JsonSubTypes.Type(value = TraceRecord.Compacted.class, name = "compacted"),
    @JsonSubTypes.Type(value = TraceRecord.ModelRequest.class, name = "request"),
    @JsonSubTypes.Type(value = TraceRecord.ToolCall.class, name = "tool"),
    @JsonSubTypes.Type(value = TraceRecord.PluginWork.class, name = "work"),
    @JsonSubTypes.Type(value = TraceRecord.HookDecision.class, name = "hook"),
    @JsonSubTypes.Type(value = TraceRecord.Question.class, name = "question")
})
public sealed interface TraceRecord {

    Head head();

    /** 只换共有字段，用于改步号和结束时刻。 */
    TraceRecord withHead(Head head);

    default String id() {
        return head().id();
    }

    /**
     * 共有字段。{@code pos} 是创建它的事件“序号.下标”（下标只数这个事件里新建的轨迹记录），客户端按它排序；
     * 分组是 {@code turn} + {@code step}，或 {@code turn} + {@code compaction}（压缩组）；手动压缩的 {@code turn} 为空。
     * {@code end} 为空表示还在进行，瞬时记录等于 {@code at}。
     */
    record Head(String id, String pos, RunId run, Integer turn, Integer step, String compaction, Instant at, Instant end) {

        public Head {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(at, "at");
        }

        public Head ended(Instant when) {
            return new Head(id, pos, run, turn, step, compaction, at, when);
        }

        public Head inStep(Integer newStep) {
            return new Head(id, pos, run, turn, newStep, compaction, at, end);
        }
    }

    /** 来源：{@code user}、{@code context}（上下文组装）、{@code plugin}（{@code name} 是钩子名）、{@code unknown}（未记录来源）。 */
    record Source(String type, String name) {
        public static final Source USER = new Source("user", null);
        public static final Source CONTEXT = new Source("context", null);
        public static final Source UNKNOWN = new Source("unknown", null);

        public static Source plugin(String hook) {
            return new Source("plugin", hook);
        }
    }

    /** system 的一段：长度（{@code String.length()}）和来源。 */
    record Segment(int chars, Source source) {}

    /** 一个运行，即一轮。{@code phase} 是内核状态名。 */
    record RunHead(Head head, Trigger trigger, String phase, EndReason endReason, String endDetail) implements TraceRecord {
        /** 提问含空闲时的插话和排队模式的直接开始；{@code FOLLOW_UP} 是从队列取出的提交。 */
        public enum Trigger { PROMPT, FOLLOW_UP, MANUAL_COMPACTION }

        @Override
        public RunHead withHead(Head newHead) {
            return new RunHead(newHead, trigger, phase, endReason, endDetail);
        }
    }

    /** 这次请求的有效 system 或工具和上一次请求不同；{@code call} 是这次尝试的命令 ID，原文从请求详情取。 */
    record SystemPrompt(Head head, boolean initial, int systemChars, List<Segment> segments, List<String> added,
                        List<String> removed, int toolCount, String call) implements TraceRecord {
        @Override
        public SystemPrompt withHead(Head newHead) {
            return new SystemPrompt(newHead, initial, systemChars, segments, added, removed, toolCount, call);
        }
    }

    /** 用户输入或插件续写。 */
    /** {@code attachments} 按上传顺序；没有时不写出。 */
    record UserInput(Head head, Mode mode, String text, Source source,
                     @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AttachmentRef> attachments) implements TraceRecord {
        public enum Mode { PROMPT, FOLLOW_UP, STEER, CONTINUE }

        public UserInput {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public UserInput(Head head, Mode mode, String text, Source source) {
            this(head, mode, text, source, List.of());
        }

        @Override
        public UserInput withHead(Head newHead) {
            return new UserInput(newHead, mode, text, source, attachments);
        }
    }

    /** 一段可变上下文的变化；新增时 {@code before} 为空，移除时 {@code after} 为空。 */
    record Section(String name, String before, String after) {}

    /** {@code toolsAdded}、{@code toolsRemoved} 是这次组装时用户开关带来的工具变化，没有时不写出。 */
    record ContextChange(Head head, List<Section> sections,
                         @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> toolsAdded,
                         @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> toolsRemoved) implements TraceRecord {

        public ContextChange(Head head, List<Section> sections) {
            this(head, sections, List.of(), List.of());
        }

        @Override
        public ContextChange withHead(Head newHead) {
            return new ContextChange(newHead, sections, toolsAdded, toolsRemoved);
        }
    }

    /** 一次压缩的结果。采用时 {@code reason} 为空；只裁剪时没有摘要。 */
    record Compacted(Head head, Compacting.Trigger trigger, boolean adopted, boolean trimOnly, long estimateBefore,
                     long estimateAfter, TokenUsage usage, String reason, String summary) implements TraceRecord {
        @Override
        public Compacted withHead(Head newHead) {
            return new Compacted(newHead, trigger, adopted, trimOnly, estimateBefore, estimateAfter, usage, reason, summary);
        }
    }

    /** 一次请求的一次尝试。{@code detail} 是供应商的原始错误，认证失败时不给。{@code retryAfter} 是失败后等了多久再试。 */
    record Attempt(String call, Instant issuedAt, Instant startedAt, Instant firstTokenAt, Instant endedAt,
                   Outcome outcome, FailureReason reason, String detail, Duration retryAfter) {
        public enum Outcome { PENDING, REPLIED, FAILED, INTERRUPTED }
    }

    /**
     * 一次请求：会话内编号，重试是同一请求的新尝试。{@code usage} 是本次，{@code total} 是会话累计；
     * {@code failure} 是最后一次失败的原因名，认证失败只给 {@code auth}。
     */
    record ModelRequest(Head head, int number, Purpose purpose, Status status, List<Attempt> attempts, int maxAttempts,
                        String provider, String actualModel, StopReason stop, int toolCalls, TokenUsage usage, TokenUsage total,
                        String text, String thinking, String partial, RequestOptions options, String failure,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> omittedImages) implements TraceRecord {
        public enum Purpose { NORMAL, SUMMARY }

        /** {@code omittedImages}：这次请求里因超出图片预算省略的附件 ID，没有时不写出。 */
        public ModelRequest {
            omittedImages = omittedImages == null ? List.of() : List.copyOf(omittedImages);
        }

        /** 等待中含流式和重试等待；中断显示为失败，部分文字可看。 */
        public enum Status { WAITING, DONE, FAILED, INTERRUPTED }

        @Override
        public ModelRequest withHead(Head newHead) {
            return new ModelRequest(newHead, number, purpose, status, attempts, maxAttempts, provider, actualModel, stop, toolCalls,
                    usage, total, text, thinking, partial, options, failure, omittedImages);
        }

        public Attempt last() {
            return attempts.getLast();
        }
    }

    /**
     * 回复里的一次工具调用。{@code group} 是发出它的事件，同组即并行；{@code issuedAt} 是发出时刻，{@code startedAt} 是真正开始执行。
     * {@code result} 原样保留（含只有空白的文字）；{@code code} 是合成结果的错误码，参数无效时为 {@code INVALID_ARGUMENTS}。
     */
    record ToolCall(Head head, String call, String name, String server, String rawArguments, Json arguments, Status status,
                    String result, String code, String reason, String group, Instant issuedAt, Instant startedAt,
                    String request) implements TraceRecord {
        /** 被拦下、没有发出、结果不明都没有自己的结果事件，由内核补的合成结果判断。 */
        public enum Status { WAITING, RUNNING, DONE, ERROR, BLOCKED, NOT_SENT, UNKNOWN }

        @Override
        public ToolCall withHead(Head newHead) {
            return new ToolCall(newHead, call, name, server, rawArguments, arguments, status, result, code, reason, group, issuedAt,
                    startedAt, request);
        }

        public boolean finished() {
            return status != Status.WAITING && status != Status.RUNNING;
        }
    }

    /**
     * 一轮插件工作。{@code hook}、{@code point}、{@code decision} 是要求它的钩子和那条钩子记录；{@code resumeAt} 是完成后回到的位置；
     * {@code after} 是之后运行继续还是结束。
     */
    record PluginWork(Head head, String command, String workType, String hook, HookPoint point, String decision, HookPoint resumeAt,
                      Json input, Status status, Json result, String continueText, After after, Instant startedAt) implements TraceRecord {
        /** 运行在工作途中结束（取消、重启）时为 {@code INTERRUPTED}。 */
        public enum Status { RUNNING, DONE, FAILED, INTERRUPTED }

        public enum After { CONTINUE, END }

        @Override
        public PluginWork withHead(Head newHead) {
            return new PluginWork(newHead, command, workType, hook, point, decision, resumeAt, input, status, result, continueText,
                    after, startedAt);
        }
    }

    /**
     * 一个钩子的决定；{@code ERROR} 是钩子抛异常（{@code HookReported}，理由是异常文字，位置可能不知道）。
     * {@code applied} 只对补丁有值：模型调用前的补丁用于这次调用或未采用，其余位置的补丁不生效。
     * 结果叫 {@code result} 而不是 {@code kind}：{@code kind} 是接口里的类型名。
     */
    record HookDecision(Head head, String hook, HookPoint point, Result result, String reason, int systemChars, List<String> tools,
                        String workType, Applied applied, String tool, String work) implements TraceRecord {
        public enum Result { STOP, NEED_WORK, PATCH, ASK, ERROR }

        public enum Applied { APPLIED, NOT_ADOPTED, INEFFECTIVE }

        static Result of(HookDecided.Kind kind) {
            return Result.valueOf(kind.name());
        }

        @Override
        public HookDecision withHead(Head newHead) {
            return new HookDecision(newHead, hook, point, result, reason, systemChars, tools, workType, applied, tool, work);
        }
    }

    /**
     * 运行停下来等用户回答：问了什么、谁问的（钩子名或 {@code ask_user}）、答了什么。
     * {@code head.at} 是问的时刻，{@code head.end} 是有结果的时刻；等人的这段不算进模型或工具的时长。
     */
    record Question(Head head, String question, String askedBy, Object title, Status status, String choice, String text)
            implements TraceRecord {

        public enum Status { WAITING, ANSWERED, EXPIRED, CANCELLED }

        @Override
        public TraceRecord withHead(Head newHead) {
            return new Question(newHead, question, askedBy, title, status, choice, text);
        }
    }
}
