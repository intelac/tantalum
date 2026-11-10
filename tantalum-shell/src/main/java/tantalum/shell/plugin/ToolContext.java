package tantalum.shell.plugin;

import java.util.Objects;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 一次工具或工作调用的上下文：替谁执行、在哪个会话里、能访问服务器上的哪个目录。插件不用从参数里取身份。
 * {@code dir} 是会话在用户电脑上的工作目录（本机工作区的工具按它解析相对路径），没有为 null。
 * 要问用户只能由钩子返回 {@code Ask}，工作里没有办法问人。
 */
public record ToolContext(Principal principal, SessionId session, Workspace workspace, String dir) {

    public ToolContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(workspace, "workspace");
    }

    /** 会话没有工作目录时。 */
    public ToolContext(Principal principal, SessionId session, Workspace workspace) {
        this(principal, session, workspace, null);
    }
}
