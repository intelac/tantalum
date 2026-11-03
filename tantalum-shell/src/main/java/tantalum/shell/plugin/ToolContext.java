package tantalum.shell.plugin;

import java.util.Objects;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/** 一次工具或工作调用的上下文：替谁执行、在哪个会话里、能访问哪个目录。插件不用从参数里取身份。 */
public record ToolContext(Principal principal, SessionId session, Workspace workspace) {

    public ToolContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(workspace, "workspace");
    }
}
