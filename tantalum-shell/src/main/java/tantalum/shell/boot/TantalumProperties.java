package tantalum.shell.boot;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.loop.Evictor;
import tantalum.shell.mcp.McpProperties;
import tantalum.shell.memory.MemoryLimits;
import tantalum.shell.model.OpenAiThinking;
import tantalum.shell.route.Levels;
import tantalum.shell.route.RouteSettings;
import tantalum.shell.settings.CompactionPolicy;

/**
 * 全部配置，默认值在 {@code application.properties}。模型是几条路线：{@code claude} 一条，加上
 * {@code openai-routes} 下任意条 OpenAI 兼容的；{@code provider} 是默认路线的名字。
 */
@ConfigurationProperties("tantalum")
public record TantalumProperties(Path dataDir, Path workspace, String workspaceMode, String systemPrompt, Duration toolTimeout,
                                 String provider, Loop loop, Claude claude, Map<String, OpenAiRoute> openaiRoutes, MemoryLimits memory,
                                 Knowledge knowledge, McpProperties mcp,
                                 Compaction compaction, Index index, Evictor.Settings residency, Duration shutdownWait, Hooks hooks,
                                 AttachmentLimits attachments, Web web) {

    /** Claude 路线的名字，固定。 */
    public static final String CLAUDE_ROUTE = "claude";
    /** Claude 的思考档位（{@code output_config.effort}），按这个顺序。 */
    public static final List<String> CLAUDE_EFFORTS = Levels.CLAUDE;
    /** 路线名只用小写字母、数字、{@code -}，最长 32。 */
    private static final Pattern ROUTE_NAME = Pattern.compile("[a-z0-9-]{1,32}");

    public TantalumProperties {
        provider = provider == null || provider.isBlank() ? CLAUDE_ROUTE : provider.strip();
        Map<String, OpenAiRoute> routes = new TreeMap<>();
        if (openaiRoutes != null) {
            for (Map.Entry<String, OpenAiRoute> route : openaiRoutes.entrySet()) {
                String name = route.getKey();
                if (!ROUTE_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("tantalum.openai-routes." + name + ": a route name uses only lowercase letters, "
                            + "digits and -, at most 32 characters");
                }
                if (name.equals(CLAUDE_ROUTE) || routes.containsKey(name)) {
                    throw new IllegalArgumentException("tantalum.openai-routes." + name + ": the route name " + name
                            + " is already taken" + (name.equals(CLAUDE_ROUTE) ? " by the Claude route" : ""));
                }
                if (route.getValue() == null || route.getValue().baseUrl() == null || route.getValue().model() == null) {
                    throw new IllegalArgumentException("tantalum.openai-routes." + name + " needs base-url and model");
                }
                routes.put(name, route.getValue());
            }
        }
        openaiRoutes = java.util.Collections.unmodifiableMap(routes);
        if (!provider.equals(CLAUDE_ROUTE) && !openaiRoutes.containsKey(provider)) {
            throw new IllegalArgumentException("tantalum.provider=" + provider + " names no route; expected claude"
                    + openaiRoutes.keySet().stream().map(name -> " or " + name).collect(java.util.stream.Collectors.joining()));
        }
    }

    /**
     * 联网：内网限制、“抓网页”、“浏览器”。管理员关掉的那一项不注册工具；两项都关时不起出口。
     * 默认值在 {@code application.properties}，没有这一段时（嵌入使用和测试）用 {@link #defaults()}。
     */
    public record Web(boolean blockPrivateNetwork, Fetch fetch, Browser browser) {

        public Web {
            fetch = fetch == null ? new Fetch(true) : fetch;
            browser = browser == null ? Browser.defaults() : browser;
        }

        public static Web defaults() {
            return new Web(true, new Fetch(true), Browser.defaults());
        }

        /** 两项里有一项开着，就要起出口。 */
        public boolean any() {
            return fetch.enabled() || browser.enabled();
        }
    }

    public record Fetch(boolean enabled) {}

    /** 浏览器：同时开着的上限（每个用户一个）、空闲多久关、Chromium 渲染进程的沙箱。上限小于 1 时启动失败。 */
    public record Browser(boolean enabled, int maxOpen, Duration idleClose, boolean sandbox) {

        public Browser {
            if (maxOpen < 1) {
                throw new IllegalArgumentException("tantalum.web.browser.max-open must be at least 1 but was " + maxOpen);
            }
            if (idleClose == null || idleClose.isNegative() || idleClose.isZero()) {
                throw new IllegalArgumentException("tantalum.web.browser.idle-close must be positive but was " + idleClose);
            }
        }

        public static Browser defaults() {
            return new Browser(true, 4, Duration.ofMinutes(10), true);
        }
    }

    /**
     * 插件工作的上限：一个等待点连续最多几轮，防止钩子每次都要工作、运行转个不停。{@code required} 是管理员设成必须开启的插件名：
     * 所有用户都开着、插件页上不能关、钩子出错按拦截处理；默认关的策略插件（如每日额度）要对所有人生效时写在这里。
     */
    public record Hooks(int maxWorkRounds, List<String> required) {

        public Hooks {
            requirePositive("maxWorkRounds", maxWorkRounds);
            required = required == null ? List.of() : required.stream().map(String::strip).filter(name -> !name.isEmpty()).toList();
        }
    }

    /** 索引库的节流：攒够事件数或到时间就写。运行结束总是写。 */
    public record Index(int flushEvents, Duration flushInterval) {

        public Index {
            requirePositive("flushEvents", flushEvents);
            requirePositive("flushInterval", flushInterval.toMillis());
        }

        public static Index defaults() {
            return new Index(200, Duration.ofSeconds(5));
        }
    }

    /**
     * 上下文压缩。窗口在各供应商下配置；这里是预留、保留量、工具结果上限和摘要长度。{@code windowFromModel} 为 false 时不向模型
     * 读窗口，只用配置的值：测试要用小窗口让压缩尽快发生。
     */
    public record Compaction(long reserveTokens, long keepRecentTokens, int maxKeptToolResultBytes,
                             int maxSummarizedToolResultBytes, long summaryMaxTokens, boolean windowFromModel) {

        public Compaction {
            requirePositive("reserveTokens", reserveTokens);
            requirePositive("keepRecentTokens", keepRecentTokens);
            requirePositive("maxKeptToolResultBytes", maxKeptToolResultBytes);
            requirePositive("maxSummarizedToolResultBytes", maxSummarizedToolResultBytes);
            requirePositive("summaryMaxTokens", summaryMaxTokens);
        }

        public static Compaction defaults() {
            return new Compaction(16384, 20000, 32 * 1024, 4 * 1024, 8192, true);
        }
    }

    /** 共享知识层：管理员维护的只读目录，里面的 Markdown 进所有用户的 system。{@code dir} 为空表示没有这一层。 */
    public record Knowledge(String dir, int maxBytes) {

        public Knowledge {
            requirePositive("maxBytes", maxBytes);
        }

        public static Knowledge none() {
            return new Knowledge("", 64 * 1024);
        }

        public Optional<Path> path() {
            return dir == null || dir.isBlank() ? Optional.empty() : Optional.of(Path.of(dir));
        }
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }

    /**
     * 环的容量。约束见 design 的“容量”一节，不满足时启动失败。{@code maxUserPermits} 是每个用户在途事件的上限，
     * 没写（0）就是 {@code inputRingSize / 4}；不能小于 {@code maxUserInFlight}，不能大于 {@code inputRingSize}。
     */
    public record Loop(int maxActiveRuns, int maxParallelTools, int maxUserInFlight, int maxExternalInFlight,
                       int inputRingSize, int outputRingSize, int maxUserPermits) {

        public Loop {
            requirePositive("maxActiveRuns", maxActiveRuns);
            requirePositive("maxParallelTools", maxParallelTools);
            requirePositive("maxUserInFlight", maxUserInFlight);
            requirePositive("maxExternalInFlight", maxExternalInFlight);
            requirePowerOfTwo("inputRingSize", inputRingSize);
            requirePowerOfTwo("outputRingSize", outputRingSize);
            long commands = (long) maxActiveRuns * (maxParallelTools + 1);
            if (outputRingSize < commands) {
                throw new IllegalArgumentException("outputRingSize " + outputRingSize + " must be at least " + commands);
            }
            long input = (long) maxActiveRuns * maxParallelTools + maxExternalInFlight;
            if (inputRingSize < input) {
                throw new IllegalArgumentException("inputRingSize " + inputRingSize + " must be at least " + input);
            }
            if (maxUserPermits == 0) {
                maxUserPermits = inputRingSize / 4;
            }
            if (maxUserPermits < maxUserInFlight || maxUserPermits > inputRingSize) {
                throw new IllegalArgumentException("tantalum.loop.max-user-permits=" + maxUserPermits + " must be between "
                        + "tantalum.loop.max-user-in-flight (" + maxUserInFlight + ") and tantalum.loop.input-ring-size (" + inputRingSize + ")");
            }
        }

        public static Loop defaults() {
            return new Loop(64, 10, 8, 256, 1024, 1024, 0);
        }

        /** 留给活跃运行结果的 Input Ring 容量。 */
        public int internalReserve() {
            return maxActiveRuns * maxParallelTools;
        }

        private static void requirePowerOfTwo(String name, int value) {
            if (Integer.bitCount(value) != 1) {
                throw new IllegalArgumentException(name + " must be a power of two but was " + value);
            }
        }
    }

    /**
     * Claude 路线（名字固定 {@code claude}）。密钥只从 {@code apiKeyEnv} 指的环境变量读（默认 {@code ANTHROPIC_API_KEY}），
     * 不在这里。{@code model}、{@code effort} 是默认模型和默认档位，{@code effort} 只能是 {@link #CLAUDE_EFFORTS} 之一；
     * {@code models} 是允许列表，空表示 Models 接口里支持 adaptive 思考的全部。{@code contextWindow} 是从 Models
     * 接口读不到窗口时用的值，0 表示未知：不压缩，控制台不显示占比。{@code bytesPerToken} 是估算系数。
     */
    public record Claude(String model, long maxTokens, String effort, boolean fallbacks, Duration firstChunkTimeout,
                         Duration betweenChunkTimeout, int concurrency, long contextWindow, double bytesPerToken,
                         String apiKeyEnv, List<String> models, String displayName) {

        public Claude {
            requirePositive("bytesPerToken", bytesPerToken);
            if (!CLAUDE_EFFORTS.contains(effort)) {
                throw new IllegalArgumentException("tantalum.claude.effort=" + effort + " is not a Claude effort; expected one of "
                        + String.join(", ", CLAUDE_EFFORTS));
            }
            apiKeyEnv = apiKeyEnv == null || apiKeyEnv.isBlank() ? "ANTHROPIC_API_KEY" : apiKeyEnv.strip();
            models = names(models);
            displayName = displayName == null || displayName.isBlank() ? "Claude" : displayName.strip();
        }
    }

    /**
     * 一条 OpenAI 兼容路线（{@code tantalum.openai-routes.{名字}.*}）。{@code apiKeyEnv} 是存密钥的环境变量名，不写就不发认证头
     * （本机服务）；{@code model} 是默认模型，{@code models} 是允许列表；{@code listModels} 为 false 时不读 {@code /models}，
     * 列表就是允许列表（没写就是默认模型）。{@code thinking} 是思考参数的写法（{@code OpenAiThinking}），{@code efforts}、
     * {@code defaultEffort} 是 {@code reasoning-effort} 的档位和默认档位；{@code vision} 表示收图片。{@code overrides}
     * 按模型名单独写这几项和窗口。没写的项用默认值：输出上限 16384、首块超时 300 秒、块间 60 秒、并发 1、窗口未知、系数 1.75。
     */
    public record OpenAiRoute(String displayName, String baseUrl, String apiKeyEnv, String model, List<String> models,
                              Boolean listModels, Integer maxTokens, Duration firstChunkTimeout, Duration betweenChunkTimeout,
                              Integer concurrency, Long contextWindow, Double bytesPerToken, Boolean vision, String thinking,
                              List<String> efforts, String defaultEffort, Map<String, ModelOverride> overrides) {

        public OpenAiRoute {
            apiKeyEnv = apiKeyEnv == null || apiKeyEnv.isBlank() ? null : apiKeyEnv.strip();
            models = names(models);
            listModels = listModels == null || listModels;
            maxTokens = maxTokens == null ? 16384 : maxTokens;
            firstChunkTimeout = firstChunkTimeout == null ? Duration.ofSeconds(300) : firstChunkTimeout;
            betweenChunkTimeout = betweenChunkTimeout == null ? Duration.ofSeconds(60) : betweenChunkTimeout;
            concurrency = concurrency == null ? 1 : concurrency;
            contextWindow = contextWindow == null ? 0 : contextWindow;
            bytesPerToken = bytesPerToken == null ? 1.75 : bytesPerToken;
            vision = vision != null && vision;
            requirePositive("maxTokens", maxTokens);
            requirePositive("concurrency", concurrency);
            requirePositive("bytesPerToken", bytesPerToken);
            ThinkingLevels levels = ThinkingLevels.of(thinking, efforts, defaultEffort);
            thinking = levels.thinking();
            efforts = levels.efforts();
            defaultEffort = levels.defaultEffort();
            Map<String, ModelOverride> byModel = new TreeMap<>();
            if (overrides != null) {
                overrides.forEach((name, override) -> byModel.put(name, override == null
                        ? new ModelOverride(null, null, null, null, null) : override));
            }
            overrides = java.util.Collections.unmodifiableMap(byModel);
            if (displayName != null && displayName.isBlank()) {
                displayName = null;
            }
        }

        /** 这个模型的单独设置，没写就是空的那份。 */
        public ModelOverride override(String model) {
            return overrides.getOrDefault(model, new ModelOverride(null, null, null, null, null));
        }
    }

    /** 一条路线里某个模型单独的设置；没写的项跟路线。写了 {@code thinking} 时档位也按它重新取默认值。 */
    public record ModelOverride(String thinking, List<String> efforts, String defaultEffort, Boolean vision, Long contextWindow) {

        public ModelOverride {
            if (thinking != null || efforts != null || defaultEffort != null) {
                ThinkingLevels levels = ThinkingLevels.of(thinking, efforts, defaultEffort);
                thinking = levels.thinking();
                efforts = levels.efforts();
                defaultEffort = levels.defaultEffort();
            }
        }
    }

    /**
     * 写法、档位和默认档位，检查过的。{@code none} 没有档位；{@code reasoning-effort} 的档位默认 low、medium、high，默认 medium；
     * 三种开关的档位是 off、on，默认 on。写法不认识、档位为空、默认档位不在档位里都抛出。
     */
    public record ThinkingLevels(String thinking, List<String> efforts, String defaultEffort) {

        static ThinkingLevels of(String thinking, List<String> efforts, String defaultEffort) {
            OpenAiThinking kind = OpenAiThinking.parse(thinking == null || thinking.isBlank() ? "none" : thinking.strip());
            Levels levels = Levels.openAi(kind, efforts, defaultEffort);
            return new ThinkingLevels(kind.configName(), levels.names(), levels.defaultLevel());
        }
    }

    /** 逗号分隔的名单：去掉空白和空项，保持顺序、去重。 */
    private static List<String> names(List<String> names) {
        if (names == null) {
            return List.of();
        }
        return names.stream().filter(java.util.Objects::nonNull).map(String::strip).filter(name -> !name.isEmpty()).distinct().toList();
    }

    /** 附件上限；没有配置时用默认值。 */
    public AttachmentLimits attachmentLimits() {
        return attachments == null ? AttachmentLimits.defaults() : attachments;
    }

    /** 联网的配置；没有配置时用默认值。 */
    public Web webSettings() {
        return web == null ? Web.defaults() : web;
    }

    /** 向模型读窗口吗；没有压缩配置段时读。 */
    public boolean windowFromModel() {
        return compaction == null || compaction.windowFromModel();
    }

    /** 和模型无关的压缩参数，和这次用的模型、用户选的压缩点一起由 {@code CompactionPolicy} 合成每次提问的设置。 */
    public CompactionPolicy.Limits compactionLimits() {
        Compaction c = compaction == null ? Compaction.defaults() : compaction;
        return new CompactionPolicy.Limits(c.reserveTokens(), c.keepRecentTokens(), c.maxKeptToolResultBytes(),
                c.maxSummarizedToolResultBytes(), c.summaryMaxTokens());
    }

    /**
     * 全部路线的配置，Claude 在前，其余按名字。配置的窗口（读不到时用）不大于预留
     * {@code max(reserve-tokens, 这条路线的输出上限)} 时抛出：启动失败（context-compaction 需求 9）。
     */
    public List<RouteSettings> routeSettings() {
        List<RouteSettings> routes = new java.util.ArrayList<>();
        routes.add(new RouteSettings(CLAUDE_ROUTE, claude.displayName(), RouteSettings.Kind.CLAUDE, claude.apiKeyEnv(), claude.model(),
                claude.models(), claude.maxTokens(), claude.contextWindow(), claude.bytesPerToken(), claude.concurrency(),
                claude.firstChunkTimeout(), claude.betweenChunkTimeout(), claude.effort(), claude.fallbacks(), null, true, null, null,
                true, Map.of()));
        openaiRoutes.forEach((name, route) -> {
            Map<String, RouteSettings.Override> overrides = new TreeMap<>();
            route.overrides().forEach((model, override) -> overrides.put(model, new RouteSettings.Override(
                    override.thinking() == null ? null : OpenAiThinking.parse(override.thinking()), override.efforts(),
                    override.defaultEffort(), override.vision(), override.contextWindow())));
            routes.add(new RouteSettings(name, route.displayName(), RouteSettings.Kind.OPENAI, route.apiKeyEnv(), route.model(),
                    route.models(), route.maxTokens(), route.contextWindow(), route.bytesPerToken(), route.concurrency(),
                    route.firstChunkTimeout(), route.betweenChunkTimeout(), route.defaultEffort(), false, route.baseUrl(),
                    route.listModels(), OpenAiThinking.parse(route.thinking()), route.efforts(), route.vision(), overrides));
        });
        long reserve = compactionLimits().reserveTokens();
        for (RouteSettings route : routes) {
            long needed = Math.max(reserve, route.maxTokens());
            if (route.contextWindow() > 0 && route.contextWindow() <= needed) {
                throw new IllegalArgumentException("the context window " + route.contextWindow() + " of route " + route.name()
                        + " must be larger than the reserve " + needed + " (max of reserve-tokens and max-tokens)");
            }
        }
        return List.copyOf(routes);
    }
}
