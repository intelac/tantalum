package tantalum.shell.wiki;

import java.util.List;
import tantalum.local.wire.DirState;
import tantalum.local.wire.WikiList;

/**
 * 给模型的知识库一段：wiki 在哪、有哪些页（路径和标题）、用户自己写的 {@code index.md}。页的内容不放，模型用读文件的工具按需去读。
 * 纯函数：同样的目录内容每次得到逐字节相同的文字。
 */
public final class WikiSection {

    public static final String NAME = "wiki";

    private WikiSection() {}

    /** 目录不可用，或者里面既没有页也没有 {@code index.md} 时是空串。 */
    public static String text(WikiList wiki) {
        if (wiki == null || wiki.state() != DirState.OK || wiki.dir() == null || (wiki.pages().isEmpty() && wiki.index() == null)) {
            return "";
        }
        StringBuilder text = new StringBuilder("## Wiki\n\nThe user keeps a wiki in ").append(escape(wiki.dir()))
                .append(". Read a page with the file tools when the task needs it; do not guess what a page says.");
        if (wiki.index() != null && !wiki.index().isBlank()) {
            // index.md 是用户写的资料，和 AGENTS.md 一样只让它跳不出包裹，不改别的
            text.append("\n\n<wiki_index file=\"index.md\">\n").append(wiki.index().strip().replace("</", "<\\/")).append("\n</wiki_index>");
        }
        List<WikiList.Page> pages = wiki.pages().size() > WikiList.MAX_PAGES ? wiki.pages().subList(0, WikiList.MAX_PAGES) : wiki.pages();
        if (!pages.isEmpty()) {
            text.append("\n\n<wiki_pages>\n");
            for (WikiList.Page page : pages) {
                text.append("- ").append(escape(page.path()));
                if (page.title() != null && !page.title().isBlank()) {
                    text.append(" — ").append(escape(title(page.title())));
                }
                text.append('\n');
            }
            int more = wiki.total() - pages.size();
            if (more > 0) {
                text.append("(").append(more).append(" more pages are not listed; use the search tools to find them.)\n");
            }
            text.append("</wiki_pages>");
        }
        return text.toString();
    }

    private static String title(String raw) {
        String flat = raw.replaceAll("\\s+", " ").strip();
        return flat.codePointCount(0, flat.length()) > WikiList.MAX_TITLE
                ? flat.substring(0, flat.offsetByCodePoints(0, WikiList.MAX_TITLE)) : flat;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ").replace("\r", " ");
    }
}
