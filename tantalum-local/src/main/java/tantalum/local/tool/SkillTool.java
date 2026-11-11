package tantalum.local.tool;

import java.util.List;
import tantalum.local.skill.Skills;
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/**
 * 模型按名字加载一个技能的全文（skills-wiki 需求 4，照 DSH-tool“工具：skill”）：当场读用户填的技能目录里那个 {@code SKILL.md}，
 * 返回 {@code <skill_content>} 整块。不列技能目录里的文件；技能提到的脚本和资料，模型用别的工具按需去用。三句错误逐字来自 DSH-tool“工具错误”。
 */
public final class SkillTool implements LocalTool {

    public static final String NAME = "skill";

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "Load a skill's full instructions by its exact name from the list of available skills. Load a skill when the "
                + "task matches its description, before acting on the task.";
    }
    @Override public Kind kind() { return Kind.SKILL; }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("name", "string", "Exact name of the skill, as written in the list of available skills.")));
    }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public LocalResult run(LocalContext context, Json arguments) {
        String name = ToolArguments.string(arguments, "name");
        String shown = name == null ? "" : name;
        if (context.skills() == null) {
            return LocalResult.error(unknown(shown));
        }
        Skills.Loaded loaded = Skills.load(context.dirs(), context.skills(), shown, Skills.Caller.MODEL);
        if (loaded.content() != null) {
            return LocalResult.ok(loaded.content());
        }
        return LocalResult.error(switch (loaded.failure()) {
            case INVALID_NAME -> "Error: invalid skill name \"" + shown + "\"";
            case NOT_FOR_MODEL -> "Error: skill \"" + shown + "\" is not available for model invocation";
            case UNKNOWN, NOT_FOR_USER, BROKEN -> unknown(shown);
        });
    }

    /** 没有这个技能、它坏了、或用户把它关了，对模型都是这一句。服务器拦下被关掉的技能时也用它。 */
    public static String unknown(String name) {
        return "Error: skill \"" + name + "\" is unknown or no longer available";
    }
}
