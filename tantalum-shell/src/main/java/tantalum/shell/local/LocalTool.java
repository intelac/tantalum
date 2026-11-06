package tantalum.shell.local;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.Tools;
import tantalum.shell.attachment.ToolImages;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/**
 * 转发工具（local-workspace 设计“一次调用”）：定义取自 {@code tantalum-local}，执行全部交给用户连着的电脑，自己不读写任何文件
 * （ArchitectureTest 钉住）。电脑送回的图片经 {@link ToolImages} 照附件规则规范化、存进本人的附件目录，再交给模型。
 * 名额：这里的 64 只是全体用户合起来的上限；每个用户的电脑同时最多 4 个，由 {@link LocalLinks} 管（L11）。
 */
public final class LocalTool implements Tool {

    /** 插件页和并发名额用的服务端名，也是总开关的名字。 */
    public static final String SERVER = LocalWorkspace.MASTER;
    static final int SERVER_CONCURRENCY = 64;

    private final tantalum.local.tool.LocalTool definition;
    private final LocalLinks links;
    private final ToolImages images;
    private final Duration standard;

    LocalTool(tantalum.local.tool.LocalTool definition, LocalLinks links, ToolImages images, Duration standard) {
        this.definition = definition;
        this.links = links;
        this.images = images;
        this.standard = standard;
    }

    /** 本机工作区的全部工具，顺序同 {@link Tools#all()}。{@code standard} 是服务器的标准工具超时。 */
    public static List<Tool> all(LocalLinks links, ToolImages images, Duration standard) {
        return Tools.all().stream().<Tool>map(definition -> new LocalTool(definition, links, images, standard)).toList();
    }

    public tantalum.local.tool.LocalTool.Kind kind() {
        return definition.kind();
    }

    @Override public String name() { return definition.name(); }
    @Override public String description() { return definition.description(); }
    @Override public Json schema() { return definition.schema(); }
    @Override public String server() { return SERVER; }
    @Override public int serverConcurrency() { return SERVER_CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return definition.isConcurrencySafe(arguments); }
    @Override public Duration timeout(Json arguments, Duration standard) { return definition.timeout(arguments, standard); }

    /** 不认 {@code /attachments/} 路径：附件用 {@code read_attachment} 读（L7），旧会话不做兼容（Yi 2026-11-06 定）。 */
    @Override
    public ToolOutput run(ToolContext context, Json arguments) throws InterruptedException, IOException {
        LocalResult result = links.call(context.principal(), context.session().value(), name(), arguments,
                definition.timeout(arguments, standard));
        if (result.images().isEmpty()) {
            return new ToolOutput(result.text(), result.error());
        }
        // 有图片时文字只是图片说明，结果就是图片引用那几行（同抽出 ToolImages 之前）
        return images.output(context.principal(), "", result.error(),
                result.images().stream().map(image -> new ToolImages.Image(image.mime(), image.bytes())).toList(),
                fileName(argument(arguments, "path")));
    }

    private static String argument(Json arguments, String name) {
        return arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Str value ? value.value() : "";
    }

    /** 电脑上的路径只取最后一段做附件的名字，免得把目录结构存进附件。 */
    private static String fileName(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }
}
