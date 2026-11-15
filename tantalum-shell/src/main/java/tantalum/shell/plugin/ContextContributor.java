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
