package tantalum.local.wire;

import java.util.List;
import java.util.Objects;

/**
 * 电脑→服务器，在 {@link ContextResult} 里（协议 4 起）：用户填的 wiki 目录里有哪些页。{@code pages} 按相对路径排序，最多
 * {@value #MAX_PAGES} 个；{@code total} 是数到的总页数（目录太大时数到 {@value #MAX_FILES} 个文件就停）。{@code index} 是根目录
 * {@code index.md} 的内容，最多 {@value #MAX_INDEX_BYTES} 字节，没有时为 null；它不算在 {@code pages} 里。
 */
public record WikiList(String dir, DirState state, List<Page> pages, int total, String index) {

    public static final int MAX_PAGES = 300;
    public static final int MAX_FILES = 5000;
    public static final int MAX_INDEX_BYTES = 16 * 1024;
    public static final int MAX_TITLE = 120;

    public WikiList {
        Objects.requireNonNull(state, "state");
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public static WikiList unusable(DirState state) {
        return new WikiList(null, state, List.of(), 0, null);
    }

    /** {@code path} 相对 wiki 目录，分隔符一律是 {@code /}；{@code title} 是文件里第一个一级标题，没有时为 null。 */
    public record Page(String path, String title) {

        public Page {
            Objects.requireNonNull(path, "path");
        }
    }
}
