package tantalum.memory.recall;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import tantalum.memory.MemoryStores;
import tantalum.memory.MemoryTools;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;
import tantalum.value.text.LocalText;

/**
 * 找回这件工作（memory-final-plan 第 9 节第 1 步）：{@link RecallHook} 在一轮第一次调模型前要它。读记忆账找，找到了用 {@code continueText}
 * 追加一条 {@code <memory-recall>} 消息，模型这一轮就看得到；结果进 {@code WorkDone}，回放确定。问题里有指代（“那个”“第二个”），
 * 或者一条都没找到时，先让本机模型改写一次问题（关思考，给中英日关键词），改写超时或失败就用原话。
 */
public final class RecallWork implements WorkType {

    public static final String TYPE = "memory-recall";
    static final long REWRITE_MILLIS = 8_000;
    private static final Pattern DEICTIC = Pattern.compile("那个|这个|第[一二三四五六七八九十\\d]+个|上面|刚才|之前说的|它|\\bthat one\\b|\\bthe (first|second|third|last) one\\b|\\bit\\b",
            Pattern.CASE_INSENSITIVE);

    /** 改写问题用的模型调用：替谁、system、给模型的文字 → 回答；失败为空。 */
    @FunctionalInterface
    public interface Rewriter {
        Optional<String> rewrite(Principal user, String system, String text);
    }

    static final String REWRITE_SYSTEM = "Rewrite the user's latest message into a standalone search query for their memory. Resolve words like "
            + "'that', 'the second one', '那个', '第二个' using the earlier messages. Reply with one line: the query, then ' | ' and 3-6 keywords "
            + "in Chinese, English and Japanese. No explanation.";

    private final MemoryStores stores;
    private final Rewriter rewriter;
    private final Function<Principal, ZoneId> zones;
    private final Semantic semantic;

    /** {@code semantic} 是按意思找的那一路；平台没有向量服务时它每次给空，找回只靠关键词。 */
    public RecallWork(MemoryStores stores, Rewriter rewriter, Function<Principal, ZoneId> zones, Semantic semantic) {
        this.stores = stores;
        this.rewriter = rewriter;
        this.zones = zones;
        this.semantic = semantic;
    }

    @Override
    public String name() {
        return TYPE;
    }

    @Override
    public String plugin() {
        return MemoryTools.PLUGIN;
    }

    @Override
    public LocalText description() {
        return LocalText.en("Recall what is relevant from memory before the model answers.").zh("回答前从记忆里想起相关的。")
                .ja("回答の前に、記憶から関係のあることを思い出します。");
    }

    @Override
    public WorkResult run(ToolContext context, Json input) {
        String question = text(input, "query");
        if (question == null || question.isBlank()) {
            return new WorkResult(new Json.Str("nothing to recall"), null);
        }
        String dir = text(input, "dir");
        List<String> recent = new ArrayList<>();
        if (input instanceof Json.Obj obj && obj.fields().get("recent") instanceof Json.Arr items) {
            items.items().forEach(item -> {
                if (item instanceof Json.Str said) {
                    recent.add(said.value());
                }
            });
        }
        ZoneId zone = zones.apply(context.principal());
        Recall recall = new Recall(stores.ledger(context.principal()));
        String extra = "";
        // 关键词一路和按意思一路合起来（向量给不出来时 near 是空的，就是原来的关键词找回）
        List<Vectors.Scored> near = semantic.near(context.principal(), question);
        Recall.Found found = recall.find(question, extra, dir, zone, near, Recall.LIMIT);
        String rewritten = null;
        if (recall.worthRewriting(question, found.hits(), DEICTIC.matcher(question).find(), dir)) {
            rewritten = rewrite(context.principal(), question, recent);
            if (rewritten != null) {
                found = recall.find(question, rewritten, dir, zone, near, Recall.LIMIT);
            }
        }
        Map<String, Json> result = new java.util.LinkedHashMap<>();
        result.put("hits", new Json.Arr(found.hits().stream().map(hit -> (Json) new Json.Str(hit.item().id())).toList()));
        if (rewritten != null) {
            result.put("rewritten", new Json.Str(rewritten));
        }
        if (!near.isEmpty()) {
            result.put("near", new Json.Num(java.math.BigDecimal.valueOf(near.size())));
        }
        return new WorkResult(new Json.Obj(result), RecallText.of(found, zone));
    }

    /** 改写一次；超过 {@link #REWRITE_MILLIS} 或失败为 null。 */
    private String rewrite(Principal user, String question, List<String> recent) {
        StringBuilder text = new StringBuilder();
        recent.forEach(earlier -> text.append("Earlier: ").append(cut(earlier)).append('\n'));
        text.append("Latest: ").append(cut(question));
        try {
            return CompletableFuture.supplyAsync(() -> rewriter.rewrite(user, REWRITE_SYSTEM, text.toString()))
                    .get(REWRITE_MILLIS, TimeUnit.MILLISECONDS).map(String::strip).filter(line -> !line.isEmpty()).orElse(null);
        } catch (Exception slowOrFailed) {
            return null;
        }
    }

    private static String cut(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 500 ? flat : flat.substring(0, 500);
    }

    private static String text(Json input, String key) {
        return input instanceof Json.Obj obj && obj.fields().get(key) instanceof Json.Str value ? value.value() : null;
    }
}
