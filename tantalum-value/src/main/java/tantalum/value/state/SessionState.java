package tantalum.value.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;

/** 一个会话。{@code messages} 是投影 prompt 用的消息视图，只追加；{@code activeRun} 没有运行时为空；
 * {@code lastRun} 是最近一次开始的运行，运行结束后仍保留，用来判断迟到的结果是否还能进历史。 */
public record SessionState(SessionId id, Principal principal, List<Message> messages,
                           RunId activeRun, RunId lastRun, List<String> followUps, SessionContext context) {

    public SessionState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principal, "principal");
        messages = List.copyOf(messages);
        followUps = List.copyOf(followUps);
    }

    public static SessionState start(SessionId id, Principal principal) {
        return new SessionState(id, principal, List.of(), null, null, List.of(), null);
    }

    public SessionState append(Message message) {
        List<Message> updated = new ArrayList<>(messages);
        updated.add(message);
        return new SessionState(id, principal, updated, activeRun, lastRun, followUps, context);
    }

    /** 开始运行时传入运行 ID，同时记为 {@code lastRun}；结束时传 null 只清 {@code activeRun}。 */
    public SessionState withActiveRun(RunId run) {
        return new SessionState(id, principal, messages, run, run == null ? lastRun : run, followUps, context);
    }

    public SessionState withFollowUps(List<String> newFollowUps) {
        return new SessionState(id, principal, messages, activeRun, lastRun, newFollowUps, context);
    }

    public SessionState withContext(SessionContext newContext) {
        return new SessionState(id, principal, messages, activeRun, lastRun, followUps, newContext);
    }

    /** 该运行是否仍是会话历史的末尾：它正在进行，或它结束后没有开始过新运行。 */
    public boolean isLatestRun(RunId run) {
        return run != null && run.equals(lastRun);
    }
}
