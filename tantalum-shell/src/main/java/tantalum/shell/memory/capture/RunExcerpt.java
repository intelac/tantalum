package tantalum.shell.memory.capture;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import tantalum.shell.mask.SecretMask;
import tantalum.shell.trace.TraceRecord;
import tantalum.shell.trace.TraceRecord.ModelRequest;
import tantalum.shell.trace.TraceRecord.ToolCall;
import tantalum.shell.trace.TraceRecord.UserInput;

/**
 * 一轮对话送去记笔记的文字（memory-capture 需求 1.1、2.2、5.2、5.4）：用户说的话、调了哪些工具（参数、结果截短并遮盖密钥）、
 * 最后的回答。注入进来的回忆块（{@code <recall>…</recall>}）去掉，免得记忆吃自己。超长时去掉中间、留头留尾。纯函数。
 */
public final class RunExcerpt {

    static final int ARGS_CHARS = 300;
    static final int RESULT_CHARS = 500;
    private static final Pattern RECALL = Pattern.compile("(?s)<recall>.*?</recall>");

    private RunExcerpt() {}

    /** {@code records} 是这一轮的轨迹记录；{@code maxChars} 是总长上限。没有用户的话也没有回答时为空串。 */
    public static String of(Collection<TraceRecord> records, int maxChars) {
        List<TraceRecord> ordered = records.stream()
                .sorted(Comparator.comparing(record -> record.head().pos(), RunExcerpt::comparePos)).toList();
        StringBuilder text = new StringBuilder();
        String answer = null;
        for (TraceRecord record : ordered) {
            switch (record) {
                case UserInput input when input.text() != null && input.mode() != UserInput.Mode.CONTINUE -> {
                    String said = clean(input.text());
                    if (!said.isBlank()) {
                        text.append("User: ").append(said).append("\n\n");
                    }
                }
                case ToolCall tool -> {
                    text.append("Tool ").append(tool.name()).append('(').append(cut(clean(tool.rawArguments()), ARGS_CHARS)).append(')');
                    if (tool.finished() && tool.status() != ToolCall.Status.DONE) {
                        text.append(" failed: ").append(tool.code() != null ? tool.code() : tool.status().name());
                    } else if (tool.result() != null) {
                        text.append(" → ").append(cut(SecretMask.mask(clean(tool.result())), RESULT_CHARS));
                    }
                    text.append("\n\n");
                }
                case ModelRequest request when request.purpose() == ModelRequest.Purpose.NORMAL && request.text() != null
                        && !request.text().isBlank() -> answer = clean(request.text());
                default -> { }
            }
        }
        if (answer != null) {
            text.append("Assistant: ").append(answer).append('\n');
        }
        return middleCut(text.toString().strip(), maxChars);
    }

    static String clean(String text) {
        return text == null ? "" : RECALL.matcher(text).replaceAll("").strip();
    }

    static String cut(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 超长时去掉中间，留开头和结尾各一半。 */
    static String middleCut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int keep = Math.max(0, (max - 40) / 2);
        int omitted = text.length() - 2 * keep;
        return text.substring(0, keep) + "\n[… " + omitted + " chars omitted …]\n" + text.substring(text.length() - keep);
    }

    private static int comparePos(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int seq = Long.compare(Long.parseLong(left[0]), Long.parseLong(right[0]));
        if (seq != 0) {
            return seq;
        }
        return Integer.compare(left.length > 1 ? Integer.parseInt(left[1]) : 0, right.length > 1 ? Integer.parseInt(right[1]) : 0);
    }
}
