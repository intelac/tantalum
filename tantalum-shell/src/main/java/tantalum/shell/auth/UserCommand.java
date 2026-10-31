package tantalum.shell.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * 用户管理命令行：{@code users add|reset|disable <name>}。密码从标准输入读，
 * 不走命令行参数，否则会出现在进程列表和 shell 历史里。
 */
public final class UserCommand {

    private static final String USAGE = "usage: users add|reset|disable <name>   (password is read from standard input)";

    private UserCommand() {}

    /** 返回进程退出码。 */
    public static int run(String[] args, Path dataDir, BufferedReader input, PrintStream out)
            throws IOException, SQLException {
        if (args.length != 2) {
            out.println(USAGE);
            return 2;
        }
        String name = args[1];
        try (UserStore store = UserStore.open(dataDir)) {
            switch (args[0]) {
                case "add" -> {
                    String password = readPassword(input);
                    if (password == null) {
                        out.println("password is required");
                        return 2;
                    }
                    store.add(name, password);
                }
                case "reset" -> {
                    String password = readPassword(input);
                    if (password == null) {
                        out.println("password is required");
                        return 2;
                    }
                    store.resetPassword(name, password);
                }
                case "disable" -> store.disable(name);
                default -> {
                    out.println(USAGE);
                    return 2;
                }
            }
            out.println("done: " + args[0] + " " + name);
            return 0;
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
