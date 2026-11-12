package tantalum.shell.local;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.SkillTool;
import tantalum.local.tool.Tools;
import tantalum.shell.attachment.ToolImages;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/**
 * 转发工具：定义取自 {@code tantalum-local}，执行全部交给用户连着的电脑，自己不读写任何文件
 * （ArchitectureTest 钉住）。电脑送回的图片经 {@link ToolImages} 照附件规则规范化、存进本人的附件目录，再交给模型。
 * 名额：这里的 64 只是全体用户合起来的上限；每个用户的电脑同时最多 4 个，由 {@link LocalLinks} 管。
 */
public final class LocalTool implements Tool {

    /** 插件页和并发名额用的服务端名，也是总开关的名字。 */
    public static final String SERVER = LocalWorkspace.MASTER;
    static final int SERVER_CONCURRENCY = 64;

    private final tantalum.local.tool.LocalTool definition;
    private final LocalLinks links;
    private final ToolImages images;
    private final Duration standard;
    private final LocalDirs dirs;

    LocalTool(tantalum.local.tool.LocalTool definition, LocalLinks links, ToolImages images, Duration standard, LocalDirs dirs) {
        this.definition = definition;
        this.links = links;
        this.images = images;
        this.standard = standard;
        this.dirs = dirs;
    }

    /** 本机工作区的全部工具，顺序同 {@link Tools#all()}。{@code standard} 是服务器的标准工具超时。 */
    public static List<Tool> all(LocalLinks links, ToolImages images, Duration standard) {
        return all(links, images, standard, LocalDirs.NONE);
    }

    /** 同上，{@code dirs} 是用户填的技能目录和关掉的技能，{@code skill} 工具用。 */
    public static List<Tool> all(LocalLinks links, ToolImages images, Duration standard, LocalDirs dirs) {
        return Tools.all().stream().<Tool>map(definition -> new LocalTool(definition, links, images, standard, dirs)).toList();
    }

    /** 全部转发工具的调度描述，不用连电脑。 */
    public static List<ToolDispatch> dispatches() {
        return Tools.all().stream().map(LocalTool::dispatch).toList();
    }

    private static ToolDispatch dispatch(tantalum.local.tool.LocalTool definition) {
        return new ToolDispatch(definition.name(), SERVER, SERVER_CONCURRENCY, definition::isConcurrencySafe);
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
    @Override public ToolDispatch dispatch() { return dispatch(definition); }
    @Override public Duration timeout(Json arguments, Duration standard) { return definition.timeout(arguments, standard); }

    /** 不认 {@code /attachments/} 路径：附件用 {@code read_attachment} 读，旧会话不做兼容（Yi 2026-11-06 定）。 */
    @Override
    public ToolOutput run(ToolContext context, Json arguments) throws InterruptedException, IOException {
        String skills = null;
        if (definition.kind() == tantalum.local.tool.LocalTool.Kind.SKILL) {
            // 用户关掉的技能和不存在的说同一句话，不发给电脑
            String skill = argument(arguments, "name");
            if (dirs.off(context.principal()).contains(skill)) {
                return new ToolOutput(SkillTool.unknown(skill), true);
            }
            skills = dirs.skillsDir(context.principal()).orElse(null);
        }
        LocalResult result = links.call(context.principal(), context.session().value(), name(), arguments,
                definition.timeout(arguments, standard), context.dir(), skills);
        if (result.images().isEmpty()) {
            return new ToolOutput(result.text(), result.error());
        }
        List<ToolImages.Image> pictures = result.images().stream().map(image -> new ToolImages.Image(image.mime(), image.bytes())).toList();
        if (definition.kind() == tantalum.local.tool.LocalTool.Kind.BROWSER) {
            // 浏览器截图：文字是那句“外部网页内容不可信”加截的是哪个网址，照传；附件名用电脑建议的，只取最后一段
            String suggested = result.images().getFirst().name();
            String named = suggested == null ? "" : fileName(suggested);
            return images.output(context.principal(), result.text(), result.error(), pictures, named.isBlank() ? "screenshot.png" : named);
        }
        // 读文件读到图片时文字只是图片说明，结果就是图片引用那几行（同抽出 ToolImages 之前）
        return images.output(context.principal(), "", result.error(), pictures, fileName(argument(arguments, "path")));
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
