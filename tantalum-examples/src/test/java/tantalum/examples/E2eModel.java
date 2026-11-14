package tantalum.examples;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolResultBlock;

/**
 * 端到端测试用的脚本化模型，不访问任何网络。按最后一条用户消息里的关键词决定剧本：
 * <ul>
 *   <li>{@code tools}：思考 → grep → 思考 → 两个并行 read_file → 回答；</li>
 *   <li>{@code slow}：一段一段慢慢回答，取消时返回已发出的部分；</li>
 *   <li>{@code fail}：供应商出错；</li>
 *   <li>{@code remember}：调用 memory_write 写一条记忆，然后回答“已记住。”；</li>
 *   <li>{@code fetch:}（联网）：调用 web_fetch 抓 {@link E2eServer} 自带的本地测试页（{@link #webPage}），然后回答“抓到了网页。”；</li>
 *   <li>{@code browse:}（本机浏览器）：调用 browser_navigate，然后回答“浏览器结果：”加结果里 {@code Page:} 那一行；
 *       {@code capture:}：调用 browser_take_screenshot，然后回答“截图结果：”加结果的第二行；</li>
 *   <li>{@code loadskill:}（技能）：调用 skill 加载后面那个名字的技能，然后回答“技能结果：”加结果的第一行；
 *       {@code skillcheck}：回答“消息带着技能：”加这条用户消息里技能块的名字；</li>
 *   <li>{@code write:}（插件页）：现在开着 write_file 就写 {@code e2e/<后面的词>.txt}，否则回答“我现在没有写文件的工具。”；
 *       {@code forcewrite:} 不管开没开都调用 write_file，看执行前的拒绝；{@code edit:} 先写一个文件再改它，看改动差异；</li>
 *   <li>{@code todo:}（todo-tool）：写三步的待办清单，每隔一会儿更新一次（第一步完成、第二步完成、全部完成），
 *       然后回答“计划做完了。”；</li>
 *   <li>{@code ask:}（waiting-state）：调用 ask_user 问“备份删还是留？”（删除 / 保留，允许补一句），然后回答“你选了：”加工具结果；</li>
 *   <li>{@code approve:}（工具审批，A4）：调用 write_file 写 {@code e2e/approve-<后面的词>.txt}，然后回答“结果：”加工具结果；
 *       用户打开 toolApproval 插件后，调用前在对话页上问；</li>
 *   <li>{@code readedit:}（本机工作区）：读电脑上的 {@code a.txt}，把 hello 改成 hi，回答“改好了：”加改动结果；</li>
 *   <li>{@code trace:}（轨迹页）：第一次调用 429，重试后一批并行工具（read_file、被钩子拦下的 read_file secret、grep），
 *       然后带引用回答；配合 {@link E2eTraceHook}；</li>
 *   <li>带附件的提问：回答“看到了附件：名字、名字。”（附件页的端到端测试）；</li>
 *   <li>其它：一句思考，然后复述用户的话。</li>
 * </ul>
 */
public final class E2eModel implements ModelClient {

    static final String MODEL = "scripted-e2e";
    /** 本地路线上没有思考档位的那个模型名（model-picker 端到端），同一个剧本。 */
    static final String PLAIN = "scripted-plain";
    static final Duration SLOW_STEP = Duration.ofMillis(250);
    static final int SLOW_STEPS = 40;

    private final AtomicInteger callIds = new AtomicInteger();
    /** 已经故意失败过一次的 {@code trace:} 提问，重试时成功。 */
    private final java.util.Set<String> rateLimited = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** {@code fetch:} 抓的网页：E2eServer 启动时设成它自带的本地测试页，不访问外网。 */
    static volatile String webPage = "http://127.0.0.1:9/article";
    /** 当前这次调用的 prompt 估算；openai 供应商并发为 1，调用是串行的。 */
    private static volatile long promptSize;

    @Override
    public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
        promptSize = promptSize(prompt);
        int lastUser = lastUserText(prompt.messages());
        String text = lastUser < 0 ? "" : userText(prompt.messages().get(lastUser));
        int results = toolResultsAfter(prompt.messages(), lastUser);
        try {
            if (!prompt.output().toolsAllowed()) {
                return summary(prompt);
            }
            if (text.contains("long")) {
                return longAnswer(text, prompt, chunks);
            }
            if (text.contains("truncate")) {
                stream(chunks, "这段回复会被截");
                return new ModelOutcome.Replied(List.of(new TextBlock("这段回复会被截")), StopReason.MAX_TOKENS, MODEL, usage(prompt));
            }
            if (text.contains(E2eTraceHook.KEYWORD)) {
                return traceScenario(text, results, chunks);
            }
            if (text.contains("secret:")) {
                // plugin-settings：调密码测试工具，原样说出结果（只有“收到了，长度 N”或“没设置”）
                return results == 0 ? reply(List.of(), call(E2eSecretTool.NAME, Map.of())) : answer(chunks, "密码工具说：" + lastResult(prompt));
            }
            if (text.contains("fetch:")) {
                return results == 0 ? reply(think(chunks, "用 web_fetch 读这个网页。"), call("web_fetch", Map.of("url", webPage)))
                        : answer(chunks, "抓到了网页。");
            }
            if (text.contains("browse:")) {
                // local-browser：模型打开一个网页，然后说出结果里的页面标题那一行
                return results == 0 ? reply(List.of(), call("browser_navigate", Map.of("url", "https://board.example/")))
                        : answer(chunks, "浏览器结果：" + lastResult(prompt).lines().filter(line -> line.startsWith("Page: ")).findFirst()
                                .orElse(lastResult(prompt)));
            }
            if (text.contains("capture:")) {
                // local-browser：模型截一张图，然后说结果的第二行（截的是哪个网址）
                return results == 0 ? reply(List.of(), call("browser_take_screenshot", Map.of()))
                        : answer(chunks, "截图结果：" + lastResult(prompt).lines().skip(1).findFirst().orElse(lastResult(prompt)));
            }
            if (text.contains("loadskill:")) {
                // skills-wiki：模型自己加载一个技能，然后说加载的结果的第一行
                return results == 0 ? reply(List.of(), call("skill", Map.of("name", word(text, "loadskill:"))))
                        : answer(chunks, "技能结果：" + lastResult(prompt).lines().findFirst().orElse(""));
            }
            if (text.contains("skillcheck")) {
                // skills-wiki：用户点名的技能有没有跟着消息到模型这里
                return answer(chunks, "消息带着技能：" + String.join("、", prompt.messages().get(lastUser).content().stream()
                        .filter(tantalum.value.model.SkillBlock.class::isInstance)
                        .map(block -> ((tantalum.value.model.SkillBlock) block).name()).toList()));
            }
            if (text.contains("tools")) {
                return toolChain(results, chunks);
            }
            if (text.contains("slow")) {
                return slow(chunks);
            }
            if (text.contains("fail")) {
                return new ModelOutcome.Failed(FailureReason.SERVER_ERROR, false, null, "", "scripted failure");
            }
            if (text.contains("remember")) {
                return remember(results, chunks);
            }
            if (text.contains("forcewrite:")) {
                return write(word(text, "forcewrite:"), true, prompt, results, chunks);
            }
            if (text.contains("write:")) {
                return write(word(text, "write:"), false, prompt, results, chunks);
            }
            if (text.contains("todo:")) {
                return todoScenario(results, chunks);
            }
            if (text.contains("ask:")) {
                // waiting-state：问用户两个选项，允许补一句；回答原样复述
                return results == 0 ? reply(List.of(), askUser()) : answer(chunks, "你选了：" + lastResult(prompt));
            }
            if (text.contains("runcmd:")) {
                // connector-jar 需求 5：“runcmd:工具 命令”，工具是 bash 或 powershell，先过“命令先问”；结果原样说出
                String rest = text.substring(text.indexOf("runcmd:") + "runcmd:".length()).strip();
                int space = rest.indexOf(' ');
                return results == 0 ? reply(List.of(), call(rest.substring(0, space), Map.of("command", rest.substring(space + 1))))
                        : answer(chunks, "命令结果：" + lastResult(prompt));
            }
            if (text.contains("approve:")) {
                String name = word(text, "approve:");
                return results == 0 ? reply(List.of(), call("write_file", Map.of("path", "e2e/approve-" + name + ".txt", "content", name + "\n")))
                        : answer(chunks, "结果：" + lastResult(prompt));
            }
            if (text.contains("grepfile:")) {
                // plugin-settings：在一个文件里 grep big-e2e-head，原样说出结果（read_file 的结果 truncateOutput 不截，演示截断用 grep）
                return results == 0 ? reply(List.of(), call("grep", Map.of("pattern", "big-e2e-head", "path", word(text, "grepfile:"))))
                        : answer(chunks, "找到：" + lastResult(prompt));
            }
            if (text.contains("readfile:")) {
                // workspaces：按相对路径读一个文件，原样说出结果；会话在工作区里时从工作区目录读
                return results == 0 ? reply(List.of(), call("read_file", Map.of("path", word(text, "readfile:"))))
                        : answer(chunks, "读到：" + lastResult(prompt));
            }
            if (text.contains("readedit:")) {
                return readEdit(results, prompt, chunks);
            }
            if (text.contains("edit:")) {
                return edit(results, prompt, chunks);
            }
            List<String> attached = attachmentNames(prompt.messages().get(lastUser));
            if (!attached.isEmpty()) {
                return answer(chunks, "看到了附件：" + String.join("、", attached) + "。");
            }
            return echo(text, chunks);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return new ModelOutcome.Interrupted(cancelled.getMessage());
        }
    }

    private ModelOutcome toolChain(int results, ChunkListener chunks) throws InterruptedException {
        return switch (results) {
            case 0 -> reply(think(chunks, "先用 grep 找一下 hello 出现在哪些文件里。"),
                    call("grep", Map.of("pattern", "hello")));
            case 1 -> reply(think(chunks, "两个文件都提到了，分别读出来。"),
                    call("read_file", Map.of("path", "notes/hello.md")),
                    call("read_file", Map.of("path", "notes/world.md")));
            default -> answer(chunks, "两个文件都读到了：\n\n- `notes/hello.md` 里是问候\n- `notes/world.md` 里是回应\n\n```text\nhello e2e\n```");
        };
    }

    private ModelOutcome traceScenario(String text, int results, ChunkListener chunks) throws InterruptedException {
        if (results == 0 && rateLimited.add(text)) {
            return new ModelOutcome.Failed(FailureReason.RATE_LIMITED, true, Duration.ofMillis(300), "", "HTTP 429: slow down");
        }
        if (results == 0) {
            // 第三个原来是 MCP 工具，通用 MCP 去掉后换成 grep（remove-generic-mcp）
            return reply(think(chunks, "并行读两个文件，再找一下回应在哪。"),
                    call("read_file", Map.of("path", "notes/hello.md")),
                    call("read_file", Map.of("path", "notes/secret.md")),
                    call("grep", Map.of("pattern", "from world")));
        }
        return answer(chunks, "问候在 notes/hello.md:3，回应在 notes/world.md:3。");
    }

    private ModelOutcome remember(int results, ChunkListener chunks) throws InterruptedException {
        if (results == 0) {
            Map<String, String> arguments = new java.util.LinkedHashMap<>();
            arguments.put("name", "e2e-fact");
            arguments.put("type", "user");
            arguments.put("description", "e2e 测试写入的记忆");
            arguments.put("content", "## e2e\n\n这是脚本化模型写入的记忆正文。");
            return reply(think(chunks, "用户让我记住，写进记忆。"), call("memory_write", arguments));
        }
        return answer(chunks, "已记住。");
    }

    /** 关键词后面的第一个词。 */
    private static String word(String text, String keyword) {
        String rest = text.substring(text.indexOf(keyword) + keyword.length()).strip();
        return rest.isEmpty() ? "out" : rest.split("\\s+")[0];
    }

    /**
     * 现在能用的工具：会话开始时没有 deferred 的，加上历史里工具变化的净结果（同 shell 的 {@code ToolChanges.active}，
     * 那边是包内的）。
     */
    static java.util.Set<String> activeTools(Prompt prompt) {
        java.util.Set<String> active = new java.util.LinkedHashSet<>();
        prompt.tools().stream().filter(tool -> !tool.deferred()).forEach(tool -> active.add(tool.name()));
        for (Message message : prompt.messages()) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolChangeBlock change) {
                    active.addAll(change.added());
                    change.removed().forEach(active::remove);
                }
            }
        }
        return active;
    }

    private ModelOutcome write(String name, boolean force, Prompt prompt, int results, ChunkListener chunks) throws InterruptedException {
        if (results == 0) {
            if (!force && !activeTools(prompt).contains("write_file")) {
                return answer(chunks, "我现在没有写文件的工具。");
            }
            String content = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 25).mapToObj(i -> "第 " + i + " 行：" + name).toList()) + "\n";
            return reply(think(chunks, "把内容写进文件。"), call("write_file", Map.of("path", "e2e/" + name + ".txt", "content", content)));
        }
        return answer(chunks, "写入结果：" + lastResult(prompt));
    }

    private ModelOutcome edit(int results, Prompt prompt, ChunkListener chunks) throws InterruptedException {
        return switch (results) {
            case 0 -> reply(think(chunks, "先写一个文件。"), call("write_file", Map.of("path", "e2e/edit.txt", "content", "alpha\nbeta\ngamma\n")));
            case 1 -> reply(think(chunks, "把 beta 改成 delta。"),
                    call("edit_file", Map.of("path", "e2e/edit.txt", "old_string", "beta", "new_string", "delta")));
            default -> answer(chunks, "改好了：" + lastResult(prompt));
        };
    }

    /** 先读再改（电脑上的 edit_file 要先读过）。 */
    private ModelOutcome readEdit(int results, Prompt prompt, ChunkListener chunks) throws InterruptedException {
        return switch (results) {
            case 0 -> reply(think(chunks, "先读 a.txt。"), call("read_file", Map.of("path", "a.txt")));
            case 1 -> reply(think(chunks, "把 hello 改成 hi。"),
                    call("edit_file", Map.of("path", "a.txt", "old_string", "hello", "new_string", "hi")));
            default -> answer(chunks, "改好了：" + lastResult(prompt));
        };
    }

    private static String lastResult(Prompt prompt) {
        for (ContentBlock block : prompt.messages().getLast().content()) {
            if (block instanceof ToolResultBlock result) {
                return result.text();
            }
        }
        return "";
    }

    /** 摘要调用：工具被禁用。摘要很短，所以总比它替代的头部小。 */
    private static ModelOutcome summary(Prompt prompt) {
        int lastUser = lastUserText(prompt.messages().subList(0, prompt.messages().size() - 1));
        String last = lastUser < 0 ? "" : userText(prompt.messages().get(lastUser)).split("\n", 2)[0];
        String text = "## Goal\n\n用户在和脚本化模型聊天，最近说的是“" + last + "”。\n\n## Key context\n\n之前的回答都是长篇填充文字。";
        return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, MODEL, usage(prompt));
    }

    /** 约 12 KB 的长回答（估算 4000 token），几轮就越过 e2e 服务器的压缩阈值。 */
    private static ModelOutcome longAnswer(String text, Prompt prompt, ChunkListener chunks) throws InterruptedException {
        String head = "长回答开始：" + text + "\n\n";
        String tail = "\n\n长回答结束：" + text;
        String filler = ("这是用来撑大上下文的填充文字，没有别的意思。").repeat(190);
        stream(chunks, head + tail);
        String answer = head + filler + tail;
        return new ModelOutcome.Replied(List.of(new TextBlock(answer)), StopReason.END_TURN, MODEL,
                usage(promptSize(prompt), tantalum.kernel.compaction.TokenEstimate.LEGACY.ofText(answer)));
    }

    private ModelOutcome slow(ChunkListener chunks) throws InterruptedException {
        StringBuilder sent = new StringBuilder();
        for (int i = 1; i <= SLOW_STEPS; i++) {
            String piece = "慢慢回答第 " + i + " 段。";
            try {
                Thread.sleep(SLOW_STEP);
            } catch (InterruptedException cancelled) {
                throw new InterruptedException(sent.toString());
            }
            chunks.onText(piece);
            sent.append(piece);
        }
        return reply(List.of(), new TextBlock(sent.toString()));
    }

    private ModelOutcome echo(String text, ChunkListener chunks) throws InterruptedException {
        List<ContentBlock> thinking = think(chunks, "用户说了一句话，直接复述。");
        String answer = "你说：" + text;
        stream(chunks, answer);
        List<ContentBlock> content = new ArrayList<>(thinking);
        content.add(new TextBlock(answer));
        return new ModelOutcome.Replied(content, StopReason.END_TURN, MODEL,
                usage(promptSize, tantalum.kernel.compaction.TokenEstimate.LEGACY.ofText(answer)));
    }

    private ModelOutcome answer(ChunkListener chunks, String text) throws InterruptedException {
        stream(chunks, text);
        return reply(List.of(), new TextBlock(text));
    }

    private static void stream(ChunkListener chunks, String text) throws InterruptedException {
        int step = Math.max(1, text.length() / 4);
        for (int at = 0; at < text.length(); at += step) {
            chunks.onText(text.substring(at, Math.min(text.length(), at + step)));
            Thread.sleep(30);
        }
    }

    private static List<ContentBlock> think(ChunkListener chunks, String text) {
        chunks.onReasoning(text);
        return List.of(new ReasoningBlock("scripted", new Json.Str(text)));
    }

    private static ModelOutcome reply(List<ContentBlock> prefix, ContentBlock... blocks) {
        List<ContentBlock> content = new ArrayList<>(prefix);
        content.addAll(List.of(blocks));
        boolean tools = content.stream().anyMatch(ToolCallBlock.class::isInstance);
        long output = 0;
        for (ContentBlock block : content) {
            if (block instanceof TextBlock text) {
                output += tantalum.kernel.compaction.TokenEstimate.LEGACY.ofText(text.text());
            }
        }
        return new ModelOutcome.Replied(content, tools ? StopReason.TOOL_USE : StopReason.END_TURN, MODEL, usage(promptSize, Math.max(output, 20)));
    }

    /** ask_user 的调用：两个选项、允许补一句（参数里有数组，{@link #call} 只放字符串）。 */
    private ToolCallBlock askUser() {
        Map<String, Json> fields = new java.util.LinkedHashMap<>();
        fields.put("question", new Json.Str("备份删还是留？"));
        fields.put("choices", new Json.Arr(List.of(new Json.Str("删除"), new Json.Str("保留"))));
        String raw = "{\"question\":\"备份删还是留？\",\"choices\":[\"删除\",\"保留\"]}";
        return new ToolCallBlock("c" + callIds.incrementAndGet(), "ask_user", raw, new Json.Obj(fields));
    }

    /** 待办清单的三步；每次更新前停一会儿，好让页面上看得到中间的样子。 */
    static final List<String> TODO_STEPS = List.of("读失败的测试", "修 apply_discount", "跑全部测试");
    static final Duration TODO_PAUSE = Duration.ofMillis(1200);

    private ModelOutcome todoScenario(int results, ChunkListener chunks) throws InterruptedException {
        if (results >= TODO_STEPS.size() + 1) {
            return answer(chunks, "计划做完了。");
        }
        if (results > 0) {
            Thread.sleep(TODO_PAUSE.toMillis());
        }
        List<Json> items = new ArrayList<>();
        StringBuilder raw = new StringBuilder("{\"todos\":[");
        for (int i = 0; i < TODO_STEPS.size(); i++) {
            String status = i < results ? "completed" : i == results ? "in_progress" : "pending";
            Map<String, Json> item = new java.util.LinkedHashMap<>();
            item.put("content", new Json.Str(TODO_STEPS.get(i)));
            item.put("status", new Json.Str(status));
            items.add(new Json.Obj(item));
            raw.append(i == 0 ? "" : ",").append("{\"content\":\"").append(TODO_STEPS.get(i)).append("\",\"status\":\"").append(status).append("\"}");
        }
        raw.append("]}");
        return reply(List.of(), new ToolCallBlock("c" + callIds.incrementAndGet(), "todo_write", raw.toString(),
                new Json.Obj(Map.of("todos", new Json.Arr(items)))));
    }

    private ToolCallBlock call(String tool, Map<String, String> arguments) {
        Map<String, Json> fields = new java.util.LinkedHashMap<>();
        StringBuilder raw = new StringBuilder("{");
        arguments.forEach((name, value) -> {
            if (raw.length() > 1) {
                raw.append(',');
            }
            raw.append('"').append(name).append("\":\"").append(value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"');
            fields.put(name, new Json.Str(value));
        });
        raw.append('}');
        return new ToolCallBlock("c" + callIds.incrementAndGet(), tool, raw.toString(), new Json.Obj(fields));
    }

    /** 用量按估算报：输入是 prompt 的估算，输出是回复的估算，这样状态栏的占比和压缩的锚点都像真的。 */
    private static TokenUsage usage(Prompt prompt) {
        return usage(promptSize(prompt), 80);
    }

    private static long promptSize(Prompt prompt) {
        return tantalum.kernel.compaction.TokenEstimate.LEGACY.ofText(prompt.system())
                + tantalum.kernel.compaction.TokenEstimate.LEGACY.ofTools(prompt.tools())
                + tantalum.kernel.compaction.TokenEstimate.LEGACY.ofMessages(prompt.messages());
    }

    private static TokenUsage usage(long promptSize, long output) {
        return new TokenUsage(Math.max(0, promptSize - 300), output, Math.min(300, promptSize), 0);
    }

    /**
     * 最后一条有文字或附件的用户消息（工具结果也是用户消息，但两样都没有）。内核追加的上下文更新（如电脑连上后“本机工作区”一段变了）
     * 也是用户消息，但不是用户写的，跳过。
     */
    private static int lastUserText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (userText(messages.get(i)).startsWith(tantalum.kernel.projection.ContextUpdate.PREFIX)) {
                continue;
            }
            if (!userText(messages.get(i)).isEmpty() || !attachmentNames(messages.get(i)).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 用户消息里附件的名字，按顺序。 */
    private static List<String> attachmentNames(Message message) {
        if (message.role() != Role.USER) {
            return List.of();
        }
        return message.content().stream().filter(AttachmentBlock.class::isInstance)
                .map(block -> ((AttachmentBlock) block).ref().name()).toList();
    }

    private static String userText(Message message) {
        if (message.role() != Role.USER) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }

    private static int toolResultsAfter(List<Message> messages, int from) {
        int count = 0;
        for (int i = from + 1; i < messages.size(); i++) {
            for (ContentBlock block : messages.get(i).content()) {
                if (block instanceof ToolResultBlock) {
                    count++;
                }
            }
        }
        return count;
    }
}
