package tantalum.shell.trace;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import tantalum.shell.mask.SecretMask;
import tantalum.shell.trace.TraceRecord.ModelRequest;
import tantalum.shell.trace.TraceRecord.ToolCall;
import tantalum.shell.trace.TraceRecord.UserInput;

/**
 * 一轮对话的文字摘录（给插件的“运行结束后”通知，memory-plugin 需求 1.1）：用户说的话、调了哪些工具（参数、结果截短并遮盖密钥）、
 * 最后的回答。插件附加的内容（{@code <attached plugin=…>…</attached>}、旧的 {@code <recall>}）去掉，免得插件吃自己附的东西。
 * 超长时去掉中间、留头留尾。纯函数。
 */
public final class RunText {

    static final int ARGS_CHARS = 300;
    static final int RESULT_CHARS = 500;
    private static final Pattern RECALL = Pattern.compile("(?s)<recall>.*?</recall>|<attached plugin=\"[^\"]*\">.*?</attached>");

    private RunText() {}

    /** {@code records} 是这一轮的轨迹记录；{@code maxChars} 是总长上限。没有用户的话也没有回答时为空串。 */
    public static String of(Collection<TraceRecord> records, int maxChars) {
        List<TraceRecord> ordered = records.stream()
                .sorted(Comparator.comparing(record -> record.head().pos(), RunText::comparePos)).toList();
        StringBuilder text = new StringBuilder();
        String answer = null;
        for (TraceRecord record : ordered) {
            switch (record) {
                case UserInput input when input.text() != null && input.mode() != UserInput.Mode.CONTINUE
                        && "user".equals(input.source().type()) -> {
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

    /**
     * 同一轮带角色的分段（memory-final-plan 第 7 节）：你说的话、每个工具的结果（截短到 {@link #SEGMENT_CHARS}、遮盖密钥）、助手每次的回答，
     * 按 journal 的顺序。{@code pluginOf} 查工具属于哪个插件（内置的为 null）。
     */
    public static List<tantalum.shell.plugin.RunSegment> segments(Collection<TraceRecord> records,
                                                                  java.util.function.Function<String, String> pluginOf) {
        List<tantalum.shell.plugin.RunSegment> segments = new java.util.ArrayList<>();
        records.stream().sorted(Comparator.comparing(record -> record.head().pos(), RunText::comparePos)).forEach(record -> {
            switch (record) {
                case UserInput input when input.text() != null && input.mode() != UserInput.Mode.CONTINUE -> {
                    String said = clean(input.text());
                    if (!"user".equals(input.source().type())) {
                        // 父会话交来的活、子会话的汇报（kernel-delegation）：不是用户的话，算委派这个工具的结果
                        segments.add(new tantalum.shell.plugin.RunSegment(tantalum.shell.plugin.RunSegment.Role.TOOL, "delegate", null,
                                cutKeepingLines(SecretMask.mask(said), SEGMENT_CHARS), record.head().pos()));
                    } else if (!said.isBlank()) {
                        segments.add(new tantalum.shell.plugin.RunSegment(tantalum.shell.plugin.RunSegment.Role.USER, null, null, said,
                                record.head().pos()));
                    }
                }
                case ToolCall tool -> {
                    String text = tool.finished() && tool.status() != ToolCall.Status.DONE
                            ? "failed: " + (tool.code() != null ? tool.code() : tool.status().name())
                            : tool.result() == null ? "" : cutKeepingLines(SecretMask.mask(clean(tool.result())), SEGMENT_CHARS);
                    segments.add(new tantalum.shell.plugin.RunSegment(tantalum.shell.plugin.RunSegment.Role.TOOL, tool.name(),
                            pluginOf.apply(tool.name()), text, record.head().pos()));
                }
                case ModelRequest request when request.purpose() == ModelRequest.Purpose.NORMAL && request.text() != null
                        && !request.text().isBlank() -> segments.add(new tantalum.shell.plugin.RunSegment(
                                tantalum.shell.plugin.RunSegment.Role.ASSISTANT, null, null, clean(request.text()), record.head().pos()));
                default -> { }
            }
        });
        return segments;
    }

    static final int SEGMENT_CHARS = 2000;

    /** 截到 {@code max} 个字符，保留换行（分段要能按行看）。 */
    static String cutKeepingLines(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
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
