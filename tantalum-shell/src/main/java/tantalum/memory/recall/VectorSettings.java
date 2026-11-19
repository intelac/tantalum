package tantalum.memory.recall;

import java.time.Duration;
import java.util.List;
import tantalum.shell.plugin.Param;
import tantalum.value.json.Json;
import tantalum.value.text.LocalText;

/**
 * 按意思找回（向量）是记忆插件的参数，在插件页上：用不用是每个人自己的开关；向量服务的地址、模型、维数只有管理员能改。
 * 这里是服务器给这些参数的默认值（{@code tantalum.memory-vectors.*}），没配地址时默认就是不用。问句前的说明、超时、第一次等多久
 * 是调服务的细节，只在服务器配置里。
 */
public record VectorSettings(String url, String model, Integer dimensions, String queryPrefix, Duration timeout, Duration firstWait) {

    public static final String ENABLED = "vectors";
    public static final String URL = "embedding-url";
    public static final String MODEL = "embedding-model";
    public static final String DIMENSIONS = "embedding-dimensions";

    public VectorSettings {
        url = url == null ? "" : url.strip();
        model = model == null ? "" : model.strip();
        dimensions = dimensions == null ? 0 : Math.max(0, dimensions);
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        firstWait = firstWait == null ? Duration.ofSeconds(4) : firstWait;
    }

    public static VectorSettings defaults() {
        return new VectorSettings(null, null, null, null, null, null);
    }

    /** 插件页上的四个参数。 */
    public List<Param> params() {
        return List.of(
                Param.toggle(ENABLED, true)
                        .label(LocalText.en("Recall by meaning").zh("按意思找回").ja("意味で思い出す"))
                        .hint(LocalText.en("Besides keywords, also find memories that mean the same in other words or another language. Works only "
                                        + "when an embedding service is set below; your memory text is sent to that address.")
                                .zh("除了关键词，再按意思找：换了说法、换了语言也找得到。下面配了向量服务才起作用；记忆的文字会发给那个地址。")
                                .ja("キーワードに加えて意味でも探します。下の埋め込みサービスが設定されているときだけ働き、記憶の文章はそのアドレスに送られます。")),
                Param.text(URL, url)
                        .label(LocalText.en("Embedding service address").zh("向量服务地址").ja("埋め込みサービスのアドレス"))
                        .hint(LocalText.en("An OpenAI-compatible /embeddings service, for example http://127.0.0.1:11434/v1. Empty turns recall by meaning off.")
                                .zh("OpenAI 兼容的 /embeddings 服务，比如 http://127.0.0.1:11434/v1。空着就不按意思找。")
                                .ja("OpenAI 互換の /embeddings サービス。例: http://127.0.0.1:11434/v1。空なら意味では探しません。"))
                        .adminOnly(),
                Param.text(MODEL, model)
                        .label(LocalText.en("Embedding model").zh("向量模型").ja("埋め込みモデル"))
                        .hint(LocalText.en("Changing it recomputes everyone's memory vectors in the background.")
                                .zh("换了模型，每个人的记忆向量会在后台重算。")
                                .ja("変更すると、全員の記憶ベクトルがバックグラウンドで再計算されます。"))
                        .adminOnly(),
                Param.number(DIMENSIONS, dimensions)
                        .label(LocalText.en("Vector dimensions").zh("向量维数").ja("ベクトルの次元数"))
                        .hint(LocalText.en("0 keeps the model's own size. A smaller number keeps only the first dimensions; set it only for models that support that.")
                                .zh("0 是用模型本来的维数。填小一些的数只取前这么多维，只给支持这种截断的模型填。")
                                .ja("0 はモデル本来の次元数。小さい数は先頭の次元だけを使います。対応するモデルでのみ設定してください。"))
                        .atLeast(0).adminOnly());
    }

    /** 这个人此刻生效的向量服务：他关了、或者没有地址和模型时是关着的。 */
    public Embeddings.Settings effective(Json.Obj values) {
        if (values.fields().get(ENABLED) instanceof Json.Bool enabled && !enabled.value()) {
            return Embeddings.Settings.OFF;
        }
        String address = values.fields().get(URL) instanceof Json.Str text ? text.value().strip() : url;
        String name = values.fields().get(MODEL) instanceof Json.Str text ? text.value().strip() : model;
        int size = values.fields().get(DIMENSIONS) instanceof Json.Num number ? Math.max(0, number.value().intValue()) : dimensions;
        return new Embeddings.Settings(address, name, size, queryPrefix, timeout);
    }
}
