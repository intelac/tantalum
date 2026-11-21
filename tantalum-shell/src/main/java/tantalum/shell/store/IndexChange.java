package tantalum.shell.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.time.ZoneOffset;
import tantalum.shell.conversation.SessionSummary;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.SessionRenamed;
import tantalum.value.event.TextWithdrawn;
import tantalum.value.event.Submitted;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;
import tantalum.value.state.RunPhase;
import tantalum.value.state.SessionState;
import tantalum.value.assignment.OpenAssignment;
import tantalum.value.state.SessionKind;

/**
 * 一个事件带给索引库的变化。比较内核处理前后的状态得到，不做 I/O；实时和重建用同一份逻辑。
 * {@code dir} 是会话的工作目录（没有为 null）；{@code rename} 是这一步的改名（没改名为 null，空串表示恢复自动标题），
 * 改名不算会话的活动。{@code swaps} 是这一步撤下的文字（{@link TextWithdrawn}；没有为空）：索引里这个会话在它之前存的文字照样换掉；
 * {@code retitle} 是撤下之后第一句话的开头（自动标题跟着换；不是撤下为 null）。撤下也不算会话的活动。
 */
public record IndexChange(Principal principal, SessionId session, long seq, Instant at, String title, String runStatus,
                          RunId activeRun, Usage usage, Submission submission, boolean runEnded,
                          Instant answerDeadline, CommandId answerTimer, String dir, String rename, Text text, boolean offRecord,
                          SessionKind kind, String issuer, List<OpenAssignment> assignments, List<TextWithdrawn.Swap> swaps, String retitle) {

    /** 内容搜索用的文字（session-find-pin R3）：{@code role} 是 {@value #USER} 或 {@value #ASSISTANT}。推理、工具不算。 */
    public record Text(String role, String text) {}

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    /** 工具调用的结果（memory-final-plan 第 9 节：history_search 也搜工具调用）：工具名、截短遮盖后的结果。侧栏的内容搜索不算它。 */
    public static final String TOOL = "tool";
    static final int TOOL_TEXT_CHARS = 600;

    /** 一个事件带来的用量，{@code seq} 在同一用户内唯一。 */
    public record Usage(long seq, LocalDate day, TokenUsage amount) {}

    public record Submission(RequestId request, SubmissionId id) {}

    public static IndexChange of(EventEnvelope envelope, KernelState before, KernelState after) {
        String text = switch (envelope.event()) {
            case UserPrompt prompt -> prompt.text();
            default -> null;
        };
        Submission submission = envelope.event() instanceof Submitted submitted
                ? new Submission(submitted.request(), submitted.submission()) : null;
        RunId active = activeRun(after, envelope.session());
        RunId previous = activeRun(before, envelope.session());
        RunPhase phase = active == null ? null : after.runs().get(active).phase();
        // 等用户回答且有期限：记下期限和定时器，重启后 shell 据此重新起定时器
        AwaitingAnswer timed = phase instanceof AwaitingAnswer waiting && waiting.timer() != null ? waiting : null;
        return new IndexChange(envelope.principal(), envelope.session(), envelope.seq(), envelope.at(),
                text == null ? "" : SessionSummary.titleOf(text),
                phase == null ? SessionSummary.IDLE : phase.name(), active,
                usage(envelope, before, after), submission, previous != null && !previous.equals(active),
                timed == null ? null : timed.question().deadline(), timed == null ? null : timed.timer(),
                dir(after, envelope.session()), envelope.event() instanceof SessionRenamed renamed ? renamed.title() : null,
                text != null && !text.isBlank() ? new Text(USER, text) : envelope.event() instanceof ToolFinished finished
                        ? toolText(finished, before, envelope.session()) : replyText(envelope), offRecord(after, envelope.session()),
                kind(after, envelope.session()), issuer(after, envelope.session()), assignments(after, envelope.session()),
                envelope.event() instanceof TextWithdrawn withdrawn ? withdrawn.swaps().stream().filter(TextWithdrawn.Swap::usable).toList() : List.of(),
                envelope.event() instanceof TextWithdrawn ? firstWords(after, envelope.session()) : null);
    }

    /** 会话第一句用户的话的开头（自动标题的来源）；还没有时为 null。 */
    private static String firstWords(KernelState state, SessionId session) {
        SessionState found = state.sessions().get(session);
        // 第一句话已经压缩掉了（history-window）：不知道，由索引对现有的标题做同样的替换
        if (found == null || found.windowStart() > 0) {
            return null;
        }
        for (int i = 0; i < found.messages().size(); i++) {
            tantalum.value.model.Message message = found.said(i);
            if (message.role() == tantalum.value.model.Role.USER && !message.content().isEmpty()
                    && message.content().getFirst() instanceof TextBlock first) {
                return SessionSummary.titleOf(first.text());
            }
        }
        return null;
    }

    /** 一次工具调用的结果：“工具名: 结果”（截短、遮盖密钥）；找不到是哪个工具时只有结果。 */
    private static Text toolText(ToolFinished finished, KernelState before, SessionId session) {
        String name = null;
        SessionState state = before.sessions().get(session);
        if (state != null) {
            // 先在窗口里找；调用那条已经压缩掉的（迟到的结果），看这一轮的工具轮
            for (int i = state.messages().size() - 1; i >= 0 && name == null; i--) {
                for (ContentBlock block : state.messages().get(i).content()) {
                    if (block instanceof tantalum.value.model.ToolCallBlock call && call.id().equals(finished.toolCallId())) {
                        name = call.name();
                    }
                }
            }
            if (name == null && state.activeRun() != null && before.runs().get(state.activeRun()) instanceof tantalum.value.state.RunState run
                    && run.tools() != null) {
                name = run.tools().slots().stream().filter(slot -> slot.call().id().equals(finished.toolCallId())).map(slot -> slot.call().name())
                        .findFirst().orElse(null);
            }
        }
        String body = finished.text() == null ? "" : tantalum.shell.mask.SecretMask.mask(finished.text()).replaceAll("\\s+", " ").strip();
        if (body.length() > TOOL_TEXT_CHARS) {
            body = body.substring(0, TOOL_TEXT_CHARS) + "…";
        }
        String line = (name == null ? "" : name + (finished.error() ? " failed: " : ": ")) + body;
        return line.isBlank() ? null : new Text(TOOL, line);
    }

    /** 模型回复里给人看的文字：全部文本块按顺序接起来；没有时为 null。 */
    private static Text replyText(EventEnvelope envelope) {
        if (!(envelope.event() instanceof ModelReplied replied)) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (ContentBlock block : replied.content()) {
            if (block instanceof TextBlock part && !part.text().isBlank()) {
                if (!joined.isEmpty()) {
                    joined.append('\n');
                }
                joined.append(part.text());
            }
        }
        return joined.isEmpty() ? null : new Text(ASSISTANT, joined.toString());
    }

    private static boolean offRecord(KernelState state, SessionId session) {
        SessionState found = state.sessions().get(session);
        return found != null && found.offRecord();
    }

    /** 会话的种类；这一步之后会话还不存在（没建就来的事件）时当作普通会话，这样的行不会被写进索引。 */
    private static SessionKind kind(KernelState state, SessionId session) {
        SessionState found = state.sessions().get(session);
        return found == null ? SessionKind.REGULAR : found.kind();
    }

    /** 帮手会话：把事交代给它的会话；不是为 null。 */
    private static String issuer(KernelState state, SessionId session) {
        SessionState found = state.sessions().get(session);
        return found == null || found.helper() == null ? null : found.helper().issuer().value();
    }

    /** 这个会话交代出去还没回来的：启动时据此核对、重新起期限的定时器。 */
    private static List<OpenAssignment> assignments(KernelState state, SessionId session) {
        SessionState found = state.sessions().get(session);
        return found == null ? List.of() : found.assignments().open();
    }

    private static String dir(KernelState state, SessionId session) {
        SessionState found = state.sessions().get(session);
        return found == null ? null : found.dir();
    }

    private static RunId activeRun(KernelState state, SessionId session) {
        SessionState found = state.sessions().get(session);
        return found == null ? null : found.activeRun();
    }

    /** 只有状态变化才是真的：内核会忽略迟到的回复。日期规则同 {@code UsageProjection}。 */
    private static Usage usage(EventEnvelope envelope, KernelState before, KernelState after) {
        PrincipalState was = before.principals().get(envelope.principal());
        PrincipalState now = after.principals().get(envelope.principal());
        if (was == now || now == null) {
            return null;
        }
        LocalDate day = LocalDate.ofInstant(envelope.at(), ZoneOffset.UTC);
        TokenUsage amount = now.usageOn(day).minus(was == null ? TokenUsage.ZERO : was.usageOn(day));
        return amount.equals(TokenUsage.ZERO) ? null : new Usage(envelope.seq(), day, amount);
    }
}
