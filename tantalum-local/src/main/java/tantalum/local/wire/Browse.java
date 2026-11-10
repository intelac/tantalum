package tantalum.local.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * 服务器→电脑（协议 3 起）：列 {@code path} 下的子目录，回复 {@link BrowseResult}。{@code path} 为 null 时列允许的目录本身。
 * 添加工作区时用，只看目录名，不读文件。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Browse(String id, String path) implements Message {

    public Browse {
        Objects.requireNonNull(id, "id");
    }
}
