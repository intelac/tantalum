package tantalum.value.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;

/** 一个会话。{@code messages} 是投影 prompt 用的消息视图，只追加；{@code activeRun} 没有运行时为空。 */
public record SessionState(SessionId id, Principal principal, List<Message> messages,
                           RunId activeRun, List<String> followUps) {

    public SessionState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principal, "principal");
        messages = List.copyOf(messages);
        followUps = List.copyOf(followUps);
    }

    public static SessionState start(SessionId id, Principal principal) {
        return new SessionState(id, principal, List.of(), null, List.of());
    }

    public SessionState append(Message message) {
        List<Message> updated = new ArrayList<>(messages);
        updated.add(message);
        return new SessionState(id, principal, updated, activeRun, followUps);
    }

    public SessionState withActiveRun(RunId run) {
        return new SessionState(id, principal, messages, run, followUps);
    }

    public SessionState withFollowUps(List<String> newFollowUps) {
        return new SessionState(id, principal, messages, activeRun, newFollowUps);
    }
}
