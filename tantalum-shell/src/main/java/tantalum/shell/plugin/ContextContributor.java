package tantalum.shell.plugin;

import java.util.Optional;
import tantalum.value.id.Principal;

/**
 * 组装上下文时插件的一份（memory-plugin 设计“Context”）：一段固定说明（放进 system，整个会话不变，缓存命中）和/或一段会变的内容
 * （放进 system 的可变段，变了由内核按段追加变化）。插件对这个用户关着时不调。
 */
public interface ContextContributor {

    String plugin();

    Optional<Contribution> contribute(Principal user);

    /**
     * 带上这一轮的情况（memory-final-plan 第 15 节：插件要知道是哪个会话、在哪个项目目录）。默认不看它，调 {@link #contribute(Principal)}。
     * 结果随事件进 journal，回放不再调。
     */
    default Optional<Contribution> contribute(Principal user, Situation situation) {
        return contribute(user);
    }

    /** 组装上下文时的情况：会话、它在用户电脑上的工作目录（没有为 null）、是不是“不进记忆”的会话。 */
    record Situation(tantalum.value.id.SessionId session, String dir, boolean offRecord) {}

    /** 这个插件会用到的段名（管理员只读查看时按它遮掉个人插件的段）。默认没有。 */
    default java.util.Set<String> sectionNames() {
        return java.util.Set.of();
    }

    /**
     * {@code fixedText} 是固定说明（可以为 null），接在基础提示后面；{@code sectionName} 和 {@code sectionBody} 是可变段的标签名和内容
     * （可以为 null 或空，空时不加这一段；内容由平台转义，插件给原文），排在各段最后；{@code byLine} 为真时内核按行比较变化。
     */
    record Contribution(String fixedText, String sectionName, String sectionBody, boolean byLine) {}
}
