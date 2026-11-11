package tantalum.local.wire;

import java.util.List;
import java.util.Objects;

/**
 * 服务器→电脑（协议 4 起）：用户在消息里点名了这几个技能，把它们当场读出来，回复 {@link SkillLoaded}。{@code skills} 是用户填的技能目录；
 * {@code session} 是哪个会话，连接程序的终端记录里写出来。
 */
public record SkillLoad(String id, String session, String skills, List<String> names) implements Message {

    /** 一条消息里最多点名这么多个。 */
    public static final int MAX_NAMES = 8;

    public SkillLoad {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(skills, "skills");
        names = names == null ? List.of() : List.copyOf(names);
    }
}
