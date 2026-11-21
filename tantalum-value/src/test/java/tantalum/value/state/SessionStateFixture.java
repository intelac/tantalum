package tantalum.value.state;

import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/** 测试里用的简短建法：一个刚建好的会话，没有目录，进记忆。 */
public final class SessionStateFixture {
    private SessionStateFixture() {}

    public static SessionState regular(SessionId id, Principal principal) {
        return SessionState.start(id, principal, SessionKind.REGULAR, null, null, false);
    }

    public static SessionState main(SessionId id, Principal principal) {
        return SessionState.start(id, principal, SessionKind.MAIN, null, null, false);
    }

    public static SessionState helper(SessionId id, Principal principal, SessionId issuer, String assignment) {
        return SessionState.start(id, principal, SessionKind.HELPER, new HelperOf(issuer, assignment, null, null, null), null, false);
    }
}
