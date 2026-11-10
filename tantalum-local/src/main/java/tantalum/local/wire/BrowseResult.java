package tantalum.local.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * 电脑→服务器：{@link Browse} 的回复。{@code path} 是列的那个目录的真实路径（列允许的目录本身时为 null）；{@code dirs} 是子目录的绝对路径，
 * 按名字排序，不含 {@code .} 开头的，最多 {@value #MAX_DIRS} 个，多了 {@code truncated} 为 true。
 * 目录不存在或不在允许的范围里时 {@code error} 是英文诊断，{@code dirs} 为空；越界和不存在说同一句话。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BrowseResult(String id, String path, List<String> dirs, boolean truncated, String error) implements Message {

    public static final int MAX_DIRS = 500;

    public BrowseResult {
        Objects.requireNonNull(id, "id");
        dirs = dirs == null ? List.of() : List.copyOf(dirs);
    }
}
