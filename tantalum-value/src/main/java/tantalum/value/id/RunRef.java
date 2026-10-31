package tantalum.value.id;

import java.util.Objects;

/** 定位一个运行：属于谁、哪个会话、哪次运行。 */
public record RunRef(Principal principal, SessionId session, RunId run) {

    public RunRef {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(run, "run");
    }
}
