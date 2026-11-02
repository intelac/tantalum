package tantalum.shell.auth;

import jakarta.servlet.http.HttpSession;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 一次登录是否还有效：账号没停用，登录后没改过密码，登录会话没过期。读库而不等通知，
 * 因为停用和改密码可能来自服务之外的命令行。
 */
public final class LoginCheck {

    /** 登录时记下的口令戳，存在 {@code HttpSession} 里。 */
    public static final String STAMP = "tantalum.passwordStamp";

    private final UserStore users;

    public LoginCheck(UserStore users) {
        this.users = users;
    }

    /** 登录成功后调用。 */
    public void remember(String name, HttpSession session) throws SQLException {
        session.setAttribute(STAMP, users.status(name).map(UserStore.Status::stamp).orElse(-1L));
    }

    /** 账号和口令戳。{@code session} 为空时只看账号。 */
    public boolean accountValid(String name, HttpSession session) throws SQLException {
        Optional<UserStore.Status> status = users.status(name);
        if (status.isEmpty() || status.get().disabled()) {
            return false;
        }
        if (session == null) {
            return true;
        }
        try {
            return session.getAttribute(STAMP) instanceof Long stamp && stamp == status.get().stamp();
        } catch (IllegalStateException invalidated) {
            return false;
        }
    }

    /**
     * 长连接用：除了账号，还自己算会话是否过期（最后访问时间加超时），不等容器的后台清理。
     * 已经作废的会话算过期。
     */
    public boolean stillValid(String name, HttpSession session, long nowMillis) throws SQLException {
        if (session != null) {
            try {
                int maxInactive = session.getMaxInactiveInterval();
                if (maxInactive > 0 && nowMillis > session.getLastAccessedTime() + maxInactive * 1000L) {
                    return false;
                }
            } catch (IllegalStateException invalidated) {
                return false;
            }
        }
        return accountValid(name, session);
    }
}
