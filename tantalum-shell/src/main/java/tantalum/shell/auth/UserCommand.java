package tantalum.shell.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tantalum.shell.cli.CliText;
import tantalum.shell.cli.CliText.Key;

/**
 * 用户管理命令行：{@code users add [--admin]|reset|disable|enable|admin|user <name>}，{@code users list}。密码从标准输入读，
 * 不走命令行参数，否则会出现在进程列表和 shell 历史里。输出按 {@link CliText} 的语言。
 * 命令行允许停用或取消自己（服务器上的人有最终控制权），但和接口一样不能让系统没有管理员。
 */
public final class UserCommand {

    private UserCommand() {}

    /** 返回进程退出码。 */
    public static int run(String[] args, Path dataDir, BufferedReader input, PrintStream out, CliText text)
            throws IOException, SQLException {
        List<String> words = new ArrayList<>(List.of(args));
        boolean admin = words.remove("--admin");
        boolean list = !words.isEmpty() && words.getFirst().equals("list");
        if (words.isEmpty() || (list ? words.size() != 1 : words.size() != 2)) {
            out.println(text.say(Key.USERS_USAGE));
            return 2;
        }
        String action = words.getFirst();
        String name = words.size() > 1 ? words.get(1) : "";
        try (UserStore store = UserStore.open(dataDir)) {
            switch (action) {
                case "add" -> {
                    String password = readPassword(input);
                    if (password == null) {
                        out.println(text.say(Key.USERS_PASSWORD_REQUIRED));
                        return 2;
                    }
                    if (password.length() < UserStore.MIN_PASSWORD) {
                        out.println(text.say(Key.USERS_PASSWORD_TOO_SHORT, Map.of("min", UserStore.MIN_PASSWORD)));
                        return 2;
                    }
                    store.add(name, password, admin);
                }
                case "reset" -> {
                    String password = readPassword(input);
                    if (password == null) {
                        out.println(text.say(Key.USERS_PASSWORD_REQUIRED));
                        return 2;
                    }
                    if (password.length() < UserStore.MIN_PASSWORD) {
                        out.println(text.say(Key.USERS_PASSWORD_TOO_SHORT, Map.of("min", UserStore.MIN_PASSWORD)));
                        return 2;
                    }
                    store.resetPassword(name, password);
                    store.logins().deleteAll(name);
                }
                case "disable" -> {
                    store.disable(name);
                    store.logins().deleteAll(name);
                }
                case "enable" -> store.enable(name);
                case "admin" -> store.setAdmin(name, true);
                case "user" -> store.setAdmin(name, false);
                case "list" -> {
                    out.println(text.say(Key.USERS_LIST_HEADER));
                    for (UserStore.Account account : store.list()) {
                        out.println(account.name() + "\t" + (account.admin() ? "admin" : "user") + "\t"
                                + (account.disabled() ? "disabled" : "enabled") + "\t" + account.createdAt());
                    }
                    return 0;
                }
                default -> {
                    out.println(text.say(Key.USERS_USAGE));
                    return 2;
                }
            }
            out.println(text.say(Key.USERS_DONE, Map.of("action", action + (admin ? " --admin" : ""), "name", name)));
            return 0;
        } catch (UserStore.Refused refused) {
            Key key = switch (refused.kind()) {
                case BAD_NAME -> Key.USERS_BAD_NAME;
                case RESERVED_NAME -> Key.USERS_RESERVED_NAME;
                case EXISTS -> Key.USERS_EXISTS;
                case NO_SUCH -> Key.USERS_NO_SUCH;
                case LAST_ADMIN -> Key.USERS_LAST_ADMIN;
            };
            out.println(text.say(key, Map.of("name", refused.name())));
            return 1;
        } catch (IllegalArgumentException failure) {
            out.println(failure.getMessage());
            return 1;
        }
    }

    private static String readPassword(BufferedReader input) throws IOException {
        String line = input.readLine();
        return line == null || line.isBlank() ? null : line;
    }
}
