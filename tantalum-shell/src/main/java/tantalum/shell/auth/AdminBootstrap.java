package tantalum.shell.auth;

import java.sql.SQLException;
import java.util.Map;

/** 没有任何用户时，由环境变量创建第一个用户，它是管理员。没有内置的默认密码。 */
public final class AdminBootstrap {

    public static final String USER = "TANTALUM_ADMIN_USER";
    public static final String PASSWORD = "TANTALUM_ADMIN_PASSWORD";

    private AdminBootstrap() {}

    public static void ensure(UserStore store, Map<String, String> environment) throws SQLException {
        if (store.count() > 0) {
            return;
        }
        String name = environment.get(USER);
        String password = environment.get(PASSWORD);
        if (name == null || name.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("No users exist yet. Set " + USER + " and " + PASSWORD
                    + " to create the first user.");
        }
        // 第一个账号是管理员（ops-hardening-2 需求 2.1）
        store.add(name, password, true);
    }
}
