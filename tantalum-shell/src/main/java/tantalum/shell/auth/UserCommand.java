package tantalum.shell.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import tantalum.shell.cli.CliText;
import tantalum.shell.cli.CliText.Key;

/**
 * 用户管理命令行：{@code users add|reset|disable <name>}。密码从标准输入读，
 * 不走命令行参数，否则会出现在进程列表和 shell 历史里。输出按 {@link CliText} 的语言（console-i18n I13）。
 */
public final class UserCommand {

    private UserCommand() {}

    /** 返回进程退出码。 */
    public static int run(String[] args, Path dataDir, BufferedReader input, PrintStream out, CliText text)
            throws IOException, SQLException {
        if (args.length != 2) {
            out.println(text.say(Key.USERS_USAGE));
            return 2;
        }
        String name = args[1];
        try (UserStore store = UserStore.open(dataDir)) {
            switch (args[0]) {
                case "add" -> {
                    String password = readPassword(input);
                    if (password == null) {
                        out.println(text.say(Key.USERS_PASSWORD_REQUIRED));
                        return 2;
                    }
                    store.add(name, password);
                }
                case "reset" -> {
                    String password = readPassword(input);
                    if (password == null) {
                        out.println(text.say(Key.USERS_PASSWORD_REQUIRED));
                        return 2;
                    }
                    store.resetPassword(name, password);
                }
                case "disable" -> store.disable(name);
                default -> {
                    out.println(text.say(Key.USERS_USAGE));
                    return 2;
                }
            }
            out.println(text.say(Key.USERS_DONE, Map.of("action", args[0], "name", name)));
            return 0;
        } catch (UserStore.Refused refused) {
            Key key = switch (refused.kind()) {
                case BAD_NAME -> Key.USERS_BAD_NAME;
                case RESERVED_NAME -> Key.USERS_RESERVED_NAME;
                case EXISTS -> Key.USERS_EXISTS;
                case NO_SUCH -> Key.USERS_NO_SUCH;
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
