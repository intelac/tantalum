package tantalum.value.state;

import java.util.List;
import java.util.Objects;
import tantalum.value.model.ToolSpec;

/** 会话第一次发给模型的 system 和工具，之后固定不变：thinking 签名绑定它们，一改旧块就失效。{@code dynamic} 是最近告知模型的可变内容。 */
public record SessionContext(String system, List<ToolSpec> tools, String dynamic) {

    public SessionContext {
        Objects.requireNonNull(system, "system");
        tools = List.copyOf(tools);
    }

    public SessionContext withDynamic(String newDynamic) {
        return new SessionContext(system, tools, newDynamic);
    }
}
