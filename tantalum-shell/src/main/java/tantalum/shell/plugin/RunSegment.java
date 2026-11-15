package tantalum.shell.plugin;

/**
 * 一轮对话里带角色的一段（memory-final-plan 第 7 节）：谁说的（你、助手、某个工具）、哪个工具、工具属于哪个插件、文字、在 journal 里的位置。
 * 平台按轨迹记录生成，不给插件平铺的文字：网页里写的“User: …”只会出现在工具的分段里，冒充不了你。
 * 工具的结果截短并遮盖密钥；插件附加给提问的块已去掉。
 */
public record RunSegment(Role role, String tool, String plugin, String text, String pos) {

    public enum Role { USER, ASSISTANT, TOOL }

    public RunSegment {
        java.util.Objects.requireNonNull(role, "role");
        text = text == null ? "" : text;
    }
}
