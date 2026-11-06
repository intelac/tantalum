package tantalum.examples;

import java.util.ArrayList;
import java.util.List;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.value.json.Json;

/**
 * {@link CitationCheckHook} 要的工作：把钩子算出的、没有根据的引用写成更正要求，作为用户消息交回模型。不做 I/O，不读文件：
 * 判断已经在钩子里做完了；钩子自己不能追加消息，只有工作的 {@code continueText} 能让运行回到模型。
 * 输入 {@code {"wrong": ["A.java:9", …]}}（{@link CitationCheckHook#input}）；名单为空时结果是 {@code "ok"}，不回到模型。
 */
public final class CitationWork implements WorkType {

    public static final String TYPE = "citation";
    /** 更正要求的开头，钩子据此数改过几次。 */
    static final String CORRECTION = "These citations are not backed by anything you read in this conversation";

    @Override
    public String name() {
        return TYPE;
    }

    @Override
    public String plugin() {
        return "citationCheck";
    }

    @Override
    public WorkResult run(ToolContext context, Json input) {
        List<String> wrong = new ArrayList<>();
        if (input instanceof Json.Obj obj && obj.fields().get("wrong") instanceof Json.Arr list) {
            for (Json item : list.items()) {
                if (item instanceof Json.Str text) {
                    wrong.add(text.value());
                }
            }
        }
        if (wrong.isEmpty()) {
            return new WorkResult(new Json.Str("ok"), null);
        }
        return new WorkResult(new Json.Str("wrong citations"), CORRECTION + ": " + String.join(", ", wrong)
                + ". Read those lines with read_file or grep, then correct your answer; drop a citation you cannot back.");
    }
}
