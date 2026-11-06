package tantalum.connect;

import java.io.PrintStream;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import tantalum.value.json.Json;

/**
 * 终端里的调用记录（local-workspace L15、需求 2.5）：每个调用一行，时间、会话、工具、路径或命令、结果，比如
 * {@code 10:02:13 s-1a2b read_file src/Order.java ok}。用户随时能看到服务器让电脑做了什么。不写文件内容，不写令牌
 * （这里拿不到令牌）。
 */
final class Journal {

    /** 一次调用的结果。 */
    enum Outcome {
        OK, DENIED, ERROR, CANCELLED;

        String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** 会话 ID 只打前几位，够分清是哪个会话。 */
    private static final int SESSION_CHARS = 8;
    /** 路径或命令最多打这么多字符，太长的截掉。 */
    private static final int SUBJECT_CHARS = 160;

    private final PrintStream out;
    private final Clock clock;

    Journal(PrintStream out, Clock clock) {
        this.out = out;
        this.clock = clock;
    }

    void call(String session, String tool, Json arguments, Outcome outcome) {
        String time = LocalTime.now(clock).format(TIME);
        String shortSession = session.length() > SESSION_CHARS ? session.substring(0, SESSION_CHARS) : session;
        out.println(time + " " + printable(shortSession, SESSION_CHARS) + " " + printable(tool, 40) + " " + subject(arguments) + " "
                + outcome.word());
    }

    /** 路径或命令：有 {@code path} 打路径，有 {@code command} 打命令原文，都没有打 {@code .}（主目录）。 */
    private static String subject(Json arguments) {
        if (arguments instanceof Json.Obj object) {
            for (String field : new String[] {"path", "command"}) {
                if (object.fields().get(field) instanceof Json.Str text && !text.value().isBlank()) {
                    return printable(text.value(), SUBJECT_CHARS);
                }
            }
        }
        return ".";
    }

    /** 控制字符和方向控制这类格式字符换成 {@code ?}，免得服务器给的参数改动用户的终端或伪装成别的路径；太长的截掉。 */
    private static String printable(String text, int max) {
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < text.length() && shown.length() < max; i++) {
            char c = text.charAt(i);
            shown.append(Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ? '?' : c);
        }
        return text.length() > max ? shown + "…" : shown.toString();
    }
}
