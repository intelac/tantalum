package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.kernel.table.StepContext;
import tantalum.value.decision.Decision;
import tantalum.value.decision.TextSwapped;
import tantalum.value.event.TextWithdrawn;
import tantalum.value.event.TextWithdrawn.Swap;
import tantalum.value.id.RunRef;
import tantalum.value.json.Json;
import tantalum.value.model.AttachedBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.Message;
import tantalum.value.model.SkillBlock;
import tantalum.value.model.TaskResultBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.Compaction;
import tantalum.value.state.ContextSection;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;

/**
 * 撤下文字（{@link TextWithdrawn}）。任何状态下都能做：只换字，不加不减消息，不动工具调用和结果的配对，不改运行的走向。
 * <p>换的地方：会话的全部历史、压缩后的替换段、排队的提问、冻结的上下文段和钩子的话；正在跑的一轮里还没并进历史的
 * 插话和已经回来的工具结果。只按字面：原文逐字出现在哪里就换哪里。哪些文字该撤下由送事件的一方定，内核不判断。
 * <p>不换的：带签名的思考块（改了提供方会拒绝）、附件、已经发出去还没回来的工具调用的参数（槽位里的那一份，
 * 历史里的那一份照换）、技能的正文（那是技能文件的内容，不是谁说的话）。
 */
public final class WithdrawSteps {

    private WithdrawSteps() {}

    public static Decision withdrawn(StepContext context) {
        TextWithdrawn event = (TextWithdrawn) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (session == null || session.lastRun() == null || !session.principal().equals(context.envelope().principal())) {
            return Decision.unchanged(context.state());
        }
        Swapper swapper = new Swapper(event.swaps().stream().filter(Swap::usable).toList());
        if (swapper.swaps.isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Compaction compaction = session.compaction() == null ? null
                : new Compaction(swapper.messages(session.compaction().replacement()), session.compaction().tailFrom());
        SessionState next = session.rewritten(swapper.messages(session.messages()), swapper.messages(session.followUps()), compaction,
                swapper.context(session.context()));
        RunState run = session.activeRun() == null ? null : context.state().runs().get(session.activeRun());
        RunState nextRun = run == null ? null : swapper.run(run);
        if (swapper.places == 0) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        out.put(next);
        if (nextRun != null) {
            out.put(nextRun);
        }
        out.report(List.of(new TextSwapped(new RunRef(session.principal(), session.id(), session.lastRun()), swapper.places)));
        return out.decision();
    }

    /** 一次撤下里的替换；{@code places} 数着换了几处。只活在一次纯函数调用里。 */
    private static final class Swapper {

        final List<Swap> swaps;
        int places;

        Swapper(List<Swap> swaps) {
            this.swaps = swaps;
        }

        String text(String text) {
            if (text == null) {
                return null;
            }
            String current = text;
            for (Swap swap : swaps) {
                int at = current.indexOf(swap.original());
                if (at < 0) {
                    continue;
                }
                StringBuilder swapped = new StringBuilder(current.length());
                int from = 0;
                while (at >= 0) {
                    swapped.append(current, from, at).append(swap.replacement());
                    from = at + swap.original().length();
                    at = current.indexOf(swap.original(), from);
                    places++;
                }
                current = swapped.append(current, from, current.length()).toString();
            }
            return current;
        }

        List<Message> messages(List<Message> messages) {
            List<Message> out = new ArrayList<>(messages.size());
            for (Message message : messages) {
                out.add(message(message));
            }
            return out;
        }

        Message message(Message message) {
            int before = places;
            List<ContentBlock> content = new ArrayList<>(message.content().size());
            for (ContentBlock block : message.content()) {
                content.add(block(block));
            }
            return places == before ? message : new Message(message.role(), content, message.interrupted());
        }

        ContentBlock block(ContentBlock block) {
            return switch (block) {
                case TextBlock text -> new TextBlock(text(text.text()));
                case ToolResultBlock result -> result(result);
                case HookTextBlock hook -> new HookTextBlock(hook.hook(), text(hook.text()));
                case AttachedBlock attached -> new AttachedBlock(attached.plugin(), text(attached.text()));
                case TaskResultBlock task -> new TaskResultBlock(task.task(), task.outcome(), text(task.text()));
                case ToolCallBlock call -> call(call);
                case SkillBlock skill -> skill;
                default -> block;
            };
        }

        ToolResultBlock result(ToolResultBlock result) {
            return new ToolResultBlock(result.toolCallId(), text(result.text()), result.error(), result.code(), result.images());
        }

        /** 参数里的字符串值照换，键名不动；换过以后原样文字重新写一遍，两份保持一致。参数不是合法 JSON 的，直接在原样文字里换。 */
        ToolCallBlock call(ToolCallBlock call) {
            int before = places;
            if (call.arguments() == null) {
                String raw = text(call.rawArguments());
                return places == before ? call : new ToolCallBlock(call.id(), call.name(), raw, null);
            }
            Json arguments = json(call.arguments());
            return places == before ? call : new ToolCallBlock(call.id(), call.name(), write(arguments), arguments);
        }

        Json json(Json value) {
            return switch (value) {
                case Json.Str text -> new Json.Str(text(text.value()));
                case Json.Arr array -> new Json.Arr(array.items().stream().map(this::json).toList());
                case Json.Obj object -> {
                    Map<String, Json> fields = new LinkedHashMap<>();
                    object.fields().forEach((key, each) -> fields.put(key, json(each)));
                    yield new Json.Obj(fields);
                }
                default -> value;
            };
        }

        SessionContext context(SessionContext context) {
            if (context == null) {
                return null;
            }
            int before = places;
            String system = text(context.system());
            List<ContextSection> sections = context.sections().stream()
                    .map(section -> new ContextSection(section.name(), text(section.body()), section.byLine())).toList();
            SessionContext.HookState hooks = context.hooks() == null ? null : new SessionContext.HookState(
                    context.hooks().texts().stream().map(hook -> new HookTextBlock(hook.hook(), text(hook.text()))).toList(),
                    context.hooks().tools(), context.hooks().active());
            return places == before ? context : new SessionContext(system, context.tools(), context.compaction(), sections,
                    context.initialTools(), context.activeTools(), context.activePlugins(), hooks, context.pluginParams(), context.unlocks());
        }

        RunState run(RunState run) {
            int before = places;
            List<Message> steers = messages(run.pendingSteers());
            ToolRound round = run.tools();
            if (round != null) {
                for (int i = 0; i < round.slots().size(); i++) {
                    ToolSlot slot = round.slots().get(i);
                    if (slot.result() != null) {
                        round = round.withSlot(i, new ToolSlot(slot.call(), slot.status(), slot.concurrencySafe(), slot.command(), result(slot.result())));
                    }
                }
            }
            return places == before ? run : run.withPendingSteers(steers).withTools(round);
        }
    }

    /** 把 JSON 值写成文字。内核不带 JSON 库；这里只在换过参数以后用一次，写法紧凑、键的顺序照原样。 */
    static String write(Json value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Json value, StringBuilder out) {
        switch (value) {
            case Json.Null nothing -> out.append("null");
            case Json.Bool bool -> out.append(bool.value());
            case Json.Num number -> out.append(number.value().toString());
            case Json.Str text -> quote(text.value(), out);
            case Json.Arr array -> {
                out.append('[');
                for (int i = 0; i < array.items().size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(array.items().get(i), out);
                }
                out.append(']');
            }
            case Json.Obj object -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Json> field : object.fields().entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(field.getKey(), out);
                    out.append(':');
                    write(field.getValue(), out);
                }
                out.append('}');
            }
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
