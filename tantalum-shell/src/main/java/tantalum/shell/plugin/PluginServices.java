package tantalum.shell.plugin;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * 平台给插件用的服务（memory-plugin 需求 1）。插件在构造时拿到它（Spring 注入），用的时候平台已经启动好。
 * 都是线程安全的；拿不到时（平台还没启动、路线不存在）返回空，不抛出。
 */
public interface PluginServices {

    /** 用哪条模型路线和模型；都为 null 时：有本机路线 {@code local} 用它，否则默认路线；模型为 null 时用那条路线的默认模型。 */
    record ModelPick(String route, String model, String effort) {

        public static final ModelPick DEFAULT = new ModelPick(null, null, null);

        /** 用路线默认的思考档位。 */
        public ModelPick(String route, String model) {
            this(route, model, null);
        }
    }

    /** 过去对话里的一句：会话、哪一方（{@code user}/{@code assistant}）、原文、时刻。 */
    record ConversationHit(SessionId session, String role, String text, Instant at) {}

    /**
     * 这个插件、这个用户的数据目录（不建，插件第一次写时按自己要的权限建）。每人工作区模式在这个人的工作区里，共用工作区时在数据目录下；
     * {@code name} 是目录名（插件自己定，小写字母、数字和 . _ -，同一插件始终用同一个）。
     */
    Path dataDir(String plugin, String name, Principal user);

    /**
     * 这个插件、这个用户的私有状态目录（不建），一律在数据目录下（{@code <data>/plugins/<插件>/<用户>}），不随工作区模式变，
     * 用户自己的工具碰不到。插件名的规矩同 {@link #dataDir}。
     */
    Path stateDir(String plugin, Principal user);

    /** 一次不带工具的模型调用，返回回答的文字；调用失败或路线不存在时为空。用量记日志，不算每日额度。 */
    Optional<String> complete(String plugin, String system, String user, ModelPick pick, int maxTokens);

    /** 排队的先后（memory-final-plan 第 15 节）：用户在等的算前台，排在后台的前面。 */
    enum Priority { FOREGROUND, BACKGROUND }

    /**
     * 一次插件的模型调用：替谁（不知道为 null）、哪个插件、做什么（记进用量账，如 {@code capture}、{@code rewrite}）、先后、
     * system、给模型的文字、路线和模型、输出上限。
     */
    record Completion(Principal user, String plugin, String purpose, Priority priority, String system, String text, ModelPick pick,
                      int maxTokens) {}

    /**
     * 同 {@link #complete(String, String, String, ModelPick, int)}，但按路线排队（本机路线一次一个，前台的先）、记进插件用量账。
     * 等不到名额时为空。默认不排队、不记账。
     */
    default Optional<String> complete(Completion request) {
        return complete(request.plugin(), request.system(), request.text(), request.pick(), request.maxTokens());
    }

    /** 这个插件此刻对这个用户生效的参数值（插件页上填的，加上管理员的默认）。 */
    Json.Obj params(String plugin, Principal user);

    /** 这个插件对这个用户开没开（插件页的开关）。 */
    boolean enabled(String plugin, Principal user);

    /** 统一的敏感信息检测（memory-final-plan 第 7 节）：所有记忆写入点都过一遍。 */
    default SensitiveText sensitive() {
        return SensitiveText.current();
    }

    /** 结束了的一轮：会话、运行、第几轮、开始和结束、结束原因、是不是手动压缩、会话是不是“不进记忆”的。 */
    record FinishedRef(SessionId session, tantalum.value.id.RunId run, Integer turn, Instant start, Instant end, String endReason,
                       boolean manual, boolean offRecord) {}

    /**
     * 一轮对话带角色的分段（memory-final-plan 第 7 节；读 journal，进行中的一轮也行：给工具判断这一轮读过外部内容没有）。
     * 不是这个人的或不存在时为空。
     */
    default List<RunSegment> runView(Principal user, SessionId session, tantalum.value.id.RunId run) {
        return List.of();
    }

    /** 这个人 {@code since} 以后开始、已经正常结束的轮次，新的在前，最多 {@code limit} 个（补抓用）。 */
    default List<FinishedRef> finishedRuns(Principal user, Instant since, int limit) {
        return List.of();
    }

    /** 在这个用户过去的对话里按文字找（不含 {@code excluding} 这个会话），最相关的在前，最多 {@code limit} 条。 */
    List<ConversationHit> searchConversations(Principal user, String query, SessionId excluding, int limit);
}
