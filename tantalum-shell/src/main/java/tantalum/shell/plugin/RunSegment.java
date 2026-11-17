package tantalum.shell.plugin;

/**
 * 一轮对话里带角色的一段（memory-final-plan 第 7 节）：谁说的（你、助手、某个工具）、哪个工具、工具属于哪个插件、文字、在 journal 里的位置。
 * 平台按轨迹记录生成，不给插件平铺的文字：网页里写的“User: …”只会出现在工具的分段里，冒充不了你。
 * 工具的结果截短并遮盖密钥；插件附加给提问的块已去掉。
 *
 * <p>问答卡（{@code ask_user}）的分段：{@code text} 只有你答的话（选的那项、填的字，不是原始 JSON），{@code asked} 是助手问的那句和
 * 给的选项。你答的才算你的话；问的那句只用来看一条要点说的是不是这次问答的事。别的分段 {@code asked} 为 null。
 */
public record RunSegment(Role role, String tool, String plugin, String text, String pos, String asked) {

    public enum Role { USER, ASSISTANT, TOOL }

    /** 不是回答问题的分段。 */
    public RunSegment(Role role, String tool, String plugin, String text, String pos) {
        this(role, tool, plugin, text, pos, null);
    }

    public RunSegment {
        java.util.Objects.requireNonNull(role, "role");
        text = text == null ? "" : text;
    }
}
