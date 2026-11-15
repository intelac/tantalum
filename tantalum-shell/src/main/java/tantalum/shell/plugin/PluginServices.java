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
    record ModelPick(String route, String model) {

        public static final ModelPick DEFAULT = new ModelPick(null, null);
    }

    /** 过去对话里的一句：会话、哪一方（{@code user}/{@code assistant}）、原文、时刻。 */
    record ConversationHit(SessionId session, String role, String text, Instant at) {}

    /**
     * 这个插件、这个用户的数据目录（不建，插件第一次写时按自己要的权限建）。每人工作区模式在这个人的工作区里，共用工作区时在数据目录下；
     * {@code name} 是目录名（插件自己定，小写字母、数字和 . _ -，同一插件始终用同一个）。
     */
    Path dataDir(String plugin, String name, Principal user);

    /** 一次不带工具的模型调用，返回回答的文字；调用失败或路线不存在时为空。用量记日志，不算每日额度。 */
    Optional<String> complete(String plugin, String system, String user, ModelPick pick, int maxTokens);

    /** 这个插件此刻对这个用户生效的参数值（插件页上填的，加上管理员的默认）。 */
    Json.Obj params(String plugin, Principal user);

    /** 这个插件对这个用户开没开（插件页的开关）。 */
    boolean enabled(String plugin, Principal user);

    /** 在这个用户过去的对话里按文字找（不含 {@code excluding} 这个会话），最相关的在前，最多 {@code limit} 条。 */
    List<ConversationHit> searchConversations(Principal user, String query, SessionId excluding, int limit);
}
