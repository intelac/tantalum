package tantalum.memory.recall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.kernel.hook.KernelHook;
import tantalum.memory.MemoryTools;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 找回的钩子（memory-final-plan 第 9 节第 1 步）：一轮第一次调模型之前要一次 {@link RecallWork}。只看事件和状态（第 3 节：钩子不读记忆账），
 * 是纯函数。这个人没有记忆（上下文里没有记忆索引和工作记忆）、已经找过（这一轮里有 {@code <memory-recall>} 消息）、调过工具、重试中、
 * 最后一条不是你的话、不进记忆的会话，都不找。
 */
public final class RecallHook implements KernelHook {

    @Override
    public String name() {
        return "memoryRecall";
    }

    @Override
    public String plugin() {
        return MemoryTools.PLUGIN;
    }

    @Override
    public HookResult beforeModelCall(KernelState state, HookContext context) {
        if (context.work() != null) {
            return Allow.PLAIN;
        }
        RunState run = state.runs().get(context.run().run());
        SessionState session = state.sessions().get(context.run().session());
        if (run == null || session == null || session.offRecord() || run.tools() != null || run.retries() > 0 || session.messages().isEmpty()) {
            return Allow.PLAIN;
        }
        // 这个人没有记忆就不找（不读记忆账：看这一轮组装上下文时记下的记忆段，回放一样）；免得每一轮都多一步工作
        boolean remembers = session.context() != null && session.context().sections().stream().anyMatch(section ->
                (section.name().equals(tantalum.memory.MemoryContext.INDEX_TAG) || section.name().equals(tantalum.memory.NowText.TAG))
                        && section.body() != null && !section.body().isBlank());
        if (!remembers) {
            return Allow.PLAIN;
        }
        // 问题后面可能跟着内核写的说明（上下文某一段变了、工具变了、换了模型）：那些不是用户说的，跳过去找问题本身。
        // 不跳的话，拿去找记忆的就是那条说明，这一轮什么也想不起来
        List<Message> messages = session.messages();
        int at = messages.size() - 1;
        while (at >= 0 && written(messages.get(at))) {
            at--;
        }
        if (at < 0) {
            return Allow.PLAIN;
        }
        Message last = messages.get(at);
        String question = textOf(last);
        if (last.role() != Role.USER || question.isBlank() || question.startsWith("<" + RecallText.TAG + ">")) {
            return Allow.PLAIN;
        }
        List<Json> recent = new ArrayList<>();
        for (int i = at - 1; i >= 0 && recent.size() < 2; i--) {
            Message earlier = messages.get(i);
            String said = textOf(earlier);
            if (earlier.role() == Role.USER && !written(earlier) && !said.isBlank() && !said.startsWith("<" + RecallText.TAG + ">")) {
                recent.add(0, new Json.Str(said.length() > 500 ? said.substring(0, 500) : said));
            }
        }
        Map<String, Json> input = new LinkedHashMap<>();
        input.put("query", new Json.Str(question.length() > 2000 ? question.substring(0, 2000) : question));
        input.put("recent", new Json.Arr(recent));
        if (session.dir() != null) {
            input.put("dir", new Json.Str(session.dir()));
        }
        return new NeedWork(RecallWork.TYPE, new Json.Obj(input));
    }

    /**
     * 不拿来找记忆的：系统消息（工具变化、钩子说明、换模型），和不是你说的用户角色消息（{@link Message#fromUser()}：上下文更新、交代出去的事的结果）。
     * 交代例外：帮手会话里拿交代的内容去找（手册“记忆 · 回答前想起”，这一步行为不变）。
     */
    private static boolean written(Message message) {
        boolean assignment = message.content().stream().anyMatch(tantalum.value.model.AssignmentBlock.class::isInstance);
        return message.role() == Role.SYSTEM || message.role() == Role.USER && !message.fromUser() && !assignment;
    }

    private static String textOf(Message message) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock part) {
                text.append(part.text());
            }
        }
        return text.toString().strip();
    }
}
