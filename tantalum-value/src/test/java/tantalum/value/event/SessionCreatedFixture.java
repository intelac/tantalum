package tantalum.value.event;

import tantalum.value.state.SessionKind;

/** 测试里用的简短建法：一个普通的新会话，没有目录，进记忆。 */
public final class SessionCreatedFixture {

    private SessionCreatedFixture() {}

    public static SessionCreated regular() {
        return new SessionCreated(SessionKind.REGULAR, null, false, null, null);
    }
}
