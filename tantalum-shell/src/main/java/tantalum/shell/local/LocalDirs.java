package tantalum.shell.local;

import java.util.Optional;
import java.util.Set;
import tantalum.value.id.Principal;

/** 用户在插件页填的两个本机目录，和他关掉的技能。目录原样交给连接程序，{@code ~/} 由它展开。 */
public interface LocalDirs {

    /** 什么都没填。 */
    LocalDirs NONE = new LocalDirs() {
        @Override public Optional<String> skillsDir(Principal user) { return Optional.empty(); }
        @Override public Optional<String> wikiDir(Principal user) { return Optional.empty(); }
        @Override public Set<String> off(Principal user) { return Set.of(); }
    };

    Optional<String> skillsDir(Principal user);

    Optional<String> wikiDir(Principal user);

    /** 这个用户关掉的技能的名字。 */
    Set<String> off(Principal user);
}
