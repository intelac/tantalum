package tantalum.shell.plugin;

import java.util.Objects;
import tantalum.value.id.Principal;

/** 一次工具或工作调用的上下文：替谁执行、能访问哪个目录。插件不用从参数里取身份。 */
public record ToolContext(Principal principal, Workspace workspace) {

    public ToolContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(workspace, "workspace");
    }
}
