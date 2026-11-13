package tantalum.examples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.kernel.hook.HookMessages;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.plugin.PluginInfo;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;
import tantalum.value.text.LocalText;

/**
 * 引用核对（{@code beforeRunEnd}）：回答里形如 {@code Order.java:42} 的引用，必须是这个会话里真的读到过的那一行。
 * 依据是会话里的工具结果：{@code read_file} 读过的行（参数里的 {@code offset} 起，按结果的行数或结尾的“Showing lines a-b”），
 * 任何工具结果里 {@code 路径:行号:} 开头的行（{@code grep}、bash 里的 {@code rg -n}），以及任何工具结果里原样出现过的
 * {@code 文件:行号}（日志里的调用栈、工单里写的位置：模型照抄的是它看到的原文，不是凭空引用）。反斜杠路径和正斜杠等同。
 * 后三条是 2026-11-14 对照 QAgent 问题清单第 5、19 条加的。
 *
 * <p>不读文件（2026-11-06 Yi 定：服务器上只有 {@code read_attachment} 读文件）。用户的代码在他自己的电脑上，服务器读不到；
 * 模型看到过什么都已经在会话里，拿工具结果核对既不用 I/O，又正好回答“这个引用有没有根据”。所以判断全在钩子里，是纯函数。
 * 附件不做依据：附件多是图片和 PDF，很少有按行号引用的源码。
 *
 * <p>结果：没有引用、引用都有根据 → 放行；有没根据的 → {@code NeedWork}（{@link CitationWork} 把更正要求交回内核，运行回到模型），
 * 改过 {@value #MAX_CORRECTIONS} 次还不对 → {@code Stop}，回答留着，结束原因写明哪几个引用没有根据。核对工作本身失败也 {@code Stop}：
 * “检查”钩子不能在检查没做成时当作通过。
 */
public final class CitationCheckHook implements KernelHook, PluginInfo {

    static final int MAX_CORRECTIONS = 2;

    /** 文件名带扩展名、冒号、行号；行号最多 9 位，再长的不是行号。 */
    private static final Pattern CITATION = Pattern.compile("([\\w./\\\\-]+\\.\\w+):(\\d{1,9})(?!\\d)");
    /** {@code read_file} 没读完时结尾的说明，见 {@code tantalum.local.tool.ReadFile}。 */
    private static final Pattern SHOWING = Pattern.compile("\\[Showing lines (\\d+)-(\\d+) of \\d+");
    /** {@code grep} 的一行：{@code 路径:行号:内容}。 */
    private static final Pattern GREP_LINE = Pattern.compile("(?m)^(.+?):(\\d{1,9}):");

    @Override
    public String name() {
        return "citationCheck";
    }

    @Override
    public LocalText description() {
        // 例子插件示范带三种语言的写法：控制台按看的人的语言选，没有就用英文
        return LocalText.en("File:line citations in an answer must point to lines read in this session; otherwise the model is asked to fix them.")
                .zh("回答里引用的 文件:行号 必须是这个会话里读到过的那一行，否则让模型改正。")
                .ja("回答で引用した ファイル:行番号 は、このセッションで読んだ行でなければなりません。違う場合はモデルに直させます。");
    }

    @Override
    public HookResult beforeRunEnd(KernelState state, HookContext context) {
        WorkDone work = context.work();
        if (work != null && work.failed() && CitationWork.TYPE.equals(work.workType())) {
            return new Stop("citation check failed: " + work.result());
        }
        SessionState session = state.sessions().get(context.run().session());
        if (session == null) {
            return Allow.PLAIN;
        }
        List<String> wrong = unsupported(lastAnswer(session.messages()), Evidence.of(session.messages()));
        if (wrong.isEmpty()) {
            return Allow.PLAIN;
        }
        if (corrections(session.messages()) >= MAX_CORRECTIONS) {
            return new Stop("citations still not backed by what was read after " + MAX_CORRECTIONS + " corrections: "
                    + String.join(", ", wrong));
        }
        return new NeedWork(CitationWork.TYPE, input(wrong));
    }

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * 像引用、其实是网络地址的：{@code 127.0.0.1:8766}、{@code http://example.com:8080/x}、{@code user@db.internal:5432}。
     * 前面是 {@code //} 或 {@code @}、后面紧跟 {@code /}、或者“文件名”是 IPv4 地址，都不当作代码引用（agent-eval 的网页场景发现：
     * 回答里写测试站的地址被当成没读过的第 8766 行，两次更正后运行被拦下）。
     */
    static boolean address(String answer, Matcher match) {
        int start = match.start();
        if (start >= 2 && answer.startsWith("//", start - 2) || start >= 1 && answer.charAt(start - 1) == '@') {
            return true;
        }
        int end = match.end();
        if (end < answer.length() && answer.charAt(end) == '/') {
            return true;
        }
        return IPV4.matcher(match.group(1)).matches();
    }

    /** {@link CitationWork} 的输入 {@code {"wrong": [...]}}。钩子不能依赖做工作的类，所以写在这里。 */
    public static Json input(List<String> wrong) {
        return new Json.Obj(Map.of("wrong", new Json.Arr(wrong.stream().<Json>map(Json.Str::new).toList())));
    }

    /** 没有根据的引用，按出现的顺序、不重复。 */
    static List<String> unsupported(String answer, Evidence evidence) {
        Set<String> wrong = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            if (address(answer, matcher)) {
                continue;
            }
            if (!evidence.covers(matcher.group(1), Long.parseLong(matcher.group(2))) && !evidence.shown(matcher.group())) {
                wrong.add(matcher.group());
            }
        }
        return List.copyOf(wrong);
    }

    /** 最后一条用户亲笔消息之后，有几条是本插件的更正要求（更正要求也是用户消息，所以从后往前数到第一条别的为止）。 */
    private static int corrections(List<Message> messages) {
        int count = 0;
        for (Message message : messages.reversed()) {
            if (!HookMessages.isUserText(message)) {
                continue;
            }
            if (!text(message).startsWith(CitationWork.CORRECTION)) {
                return count;
            }
            count++;
        }
        return count;
    }

    private static String lastAnswer(List<Message> messages) {
        for (Message message : messages.reversed()) {
            if (message.role() == Role.ASSISTANT) {
                return text(message);
            }
        }
        return "";
    }

    private static String text(Message message) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock plain) {
                text.append(plain.text());
            }
        }
        return text.toString();
    }

    /** 会话里读到过的行：路径 → 行号区间。 */
    record Evidence(Map<String, List<long[]>> lines, List<String> texts) {

        static Evidence of(List<Message> messages) {
            Map<String, ToolCallBlock> calls = new HashMap<>();
            Map<String, List<long[]>> lines = new HashMap<>();
            List<String> texts = new ArrayList<>();
            for (Message message : messages) {
                for (ContentBlock block : message.content()) {
                    if (block instanceof ToolCallBlock call) {
                        calls.put(call.id(), call);
                    } else if (block instanceof ToolResultBlock result && !result.error() && result.text() != null) {
                        ToolCallBlock call = calls.get(result.toolCallId());
                        if (call != null) {
                            read(call, result.text(), lines);
                            texts.add(result.text());
                        }
                    }
                }
            }
            return new Evidence(lines, texts);
        }

        private static void read(ToolCallBlock call, String text, Map<String, List<long[]>> lines) {
            Map<String, Json> arguments = call.arguments() instanceof Json.Obj obj ? obj.fields() : Map.of();
            switch (call.name()) {
                case "read_file" -> {
                    if (!(arguments.get("path") instanceof Json.Str path)) {
                        return;
                    }
                    Matcher showing = SHOWING.matcher(text);
                    long first;
                    long last;
                    if (showing.find()) {
                        first = Long.parseLong(showing.group(1));
                        last = Long.parseLong(showing.group(2));
                    } else {
                        first = arguments.get("offset") instanceof Json.Num offset ? offset.value().longValue() : 1;
                        last = first + text.split("\n", -1).length - 1;
                    }
                    add(lines, path.value(), first, last);
                }
                default -> {
                    // 下面的“路径:行号:”对所有工具都算
                }
            }
            // grep、bash 里的 rg -n / grep -n、本机 MCP 的搜索：每行“路径:行号:内容”都算读过那一行
            Matcher hit = GREP_LINE.matcher(text);
            while (hit.find()) {
                long line = Long.parseLong(hit.group(2));
                add(lines, hit.group(1), line, line);
            }
        }

        private static void add(Map<String, List<long[]>> lines, String path, long first, long last) {
            lines.computeIfAbsent(normalize(path), ignored -> new ArrayList<>()).add(new long[] {first, last});
        }

        /** 引用的路径和读过的路径一样，或者一个是另一个的结尾（{@code Order.java} 对 {@code src/Order.java}）。 */
        boolean covers(String path, long line) {
            String cited = normalize(path);
            for (Map.Entry<String, List<long[]>> entry : lines.entrySet()) {
                String read = entry.getKey();
                boolean same = read.equals(cited) || read.endsWith("/" + cited) || cited.endsWith("/" + read);
                if (same && entry.getValue().stream().anyMatch(range -> range[0] <= line && line <= range[1])) {
                    return true;
                }
            }
            return false;
        }

        /** 引用原样出现在某个工具结果里（前面不是路径的一部分，后面不再跟数字）：调用栈、工单里的位置。 */
        boolean shown(String citation) {
            Pattern exact = Pattern.compile("(?<![\\w.-])" + Pattern.quote(citation) + "(?!\\d)");
            return texts.stream().anyMatch(text -> exact.matcher(text).find());
        }

        private static String normalize(String path) {
            String trimmed = path.strip().replace('\\', '/');
            while (trimmed.startsWith("./")) {
                trimmed = trimmed.substring(2);
            }
            return trimmed;
        }
    }
}
