package tantalum.examples;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.shell.plugin.Configurable;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;
import tantalum.value.text.LocalText;

/**
 * 端到端测试用的密码工具（plugin-settings P16）：声明一个密码参数，调用时只说收没收到、有多长，不回显原文。
 * 示例插件里不放要联网的密码例子（拿用户填的地址发请求等于让服务器替人探内网），密码从页面到工具的全流程用它验。
 */
public final class E2eSecretTool implements Tool, Configurable {

    public static final String NAME = "e2e_secret_echo";
    static final String KEY = "token";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Reports whether the user's test token is set, and its length.";
    }

    @Override
    public Json schema() {
        return new Json.Obj(Map.of("type", new Json.Str("object")));
    }

    @Override
    public List<Param> params() {
        return List.of(Param.secret(KEY).label(LocalText.en("Test token").zh("测试密码").ja("テスト用トークン")));
    }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        Optional<String> token = context.secrets().get(KEY);
        return ToolOutput.ok(token.map(value -> "收到了，长度 " + value.length()).orElse("没设置"));
    }
}
