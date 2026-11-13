package tantalum.shell.mask;

import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.PluginInfo;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;
import tantalum.value.text.LocalText;

/**
 * 内置的密钥遮盖（QAgent 问题 67）：工具结果交给模型、写进记录、显示在页面上之前，把常见形式的密钥换成 {@code [REDACTED]}，
 * 见 {@link SecretMask}。默认开，每个用户能在插件页关；{@code tantalum.secret-mask.enabled=false} 整个去掉。排在最前，先于截断这类
 * 插件，免得密钥被截成半截认不出来。出错按拦截处理：遮盖没做成时不能把原文放过去。
 */
public final class SecretMaskHook implements AfterToolHook, PluginInfo {

    public static final String NAME = "secretMask";

    @Override
    public String plugin() {
        return NAME;
    }

    @Override
    public LocalText description() {
        return LocalText.en("Replaces API keys, tokens, passwords and private keys in tool results with [REDACTED] before the model, "
                        + "the record and the page see them.")
                .zh("工具结果交给模型、写进记录、显示在页面上之前，把里面的 API key、令牌、密码和私钥换成 [REDACTED]。")
                .ja("ツール結果をモデル・記録・ページに渡す前に、API キー、トークン、パスワード、秘密鍵を [REDACTED] に置き換えます。");
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public boolean failClosed() {
        return true;
    }

    @Override
    public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) {
        String masked = SecretMask.mask(output.text());
        return masked == null || masked.equals(output.text()) ? output : new ToolOutput(masked, output.error(), output.images());
    }
}
