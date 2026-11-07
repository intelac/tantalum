package tantalum.shell.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * HTML 转 Markdown，在 jsoup 的 DOM 上自己写，不另引转换库。规则照 DSH 的 turndown 设置
 * （{@code web/tool-web/src/fetch.ts:25-49}）：先删掉不显示的内容；标题用 {@code #}，代码块用 {@code ```}，列表用 {@code -}，
 * 链接和图片转成绝对网址，表格转成 GFM 管道表，连续空行压成一个。
 * <p>做法：先把每个块的文字整理好（压空白、去行首行尾空格），再给列表、引用加前缀。缩进和代码块里的空白、换行先换成私用区字符，
 * 整理时不会被压掉，最后再换回来。
 */
final class HtmlMarkdown {

    /** 不显示的元素（DSH {@code fetch.ts:31-49}）。 */
    private static final String HIDDEN = "script, style, noscript, template, iframe, object, embed, [hidden], [aria-hidden=true], "
            + "input[type=hidden]";
    private static final Pattern HIDDEN_STYLE = Pattern.compile(
            "(?i)(^|;)\\s*(display\\s*:\\s*none|visibility\\s*:\\s*(hidden|collapse))\\s*(!important)?\\s*(;|$)");
    private static final Set<String> BLOCKS = Set.of("p", "div", "section", "article", "header", "footer", "main", "nav", "aside",
            "form", "fieldset", "figure", "figcaption", "address", "dl", "dt", "dd", "details", "summary", "center", "hgroup",
            "body", "html", "li", "caption");

    /** 缩进的空格、代码里的空格、制表符、换行。网页里本来就有的这几个字符先换成空格。 */
    private static final char SPACE = '';
    private static final char TAB = '';
    private static final char NEWLINE = '';
    private static final Pattern PRIVATE = Pattern.compile("[-]");

    private HtmlMarkdown() {}

    /** 会改动 {@code document}（删掉不显示的元素）；调用方自己的文档，用完就丢。 */
    static String of(Document document) {
        document.select(HIDDEN).remove();
        for (Element styled : document.select("[style]")) {
            if (HIDDEN_STYLE.matcher(styled.attr("style")).find()) {
                styled.remove();
            }
        }
        Element root = document.body() == null ? document : document.body();
        String markdown = tidy(children(root));
        return markdown.replace(SPACE, ' ').replace(TAB, '\t').replace(NEWLINE, '\n');
    }

    private static String children(Node parent) {
        StringBuilder out = new StringBuilder();
        for (Node child : parent.childNodes()) {
            if (child instanceof TextNode text) {
                out.append(PRIVATE.matcher(text.getWholeText()).replaceAll(" ").replaceAll("[\\s ]+", " "));
            } else if (child instanceof Element element) {
                out.append(element(element));
            }
        }
        return out.toString();
    }

    private static String element(Element e) {
        String name = e.normalName();
        return switch (name) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                String text = oneLine(children(e));
                yield text.isEmpty() ? "" : "\n\n" + "#".repeat(name.charAt(1) - '0') + " " + text + "\n\n";
            }
            case "br" -> "\n";
            case "hr" -> "\n\n---\n\n";
            case "pre" -> pre(e);
            case "code", "kbd", "samp", "tt" -> code(e.text());
            case "a" -> link(e);
            case "img" -> image(e);
            case "strong", "b" -> wrap("**", children(e));
            case "em", "i" -> wrap("_", children(e));
            case "del", "s", "strike" -> wrap("~~", children(e));
            case "ul", "ol" -> list(e);
            case "blockquote" -> quote(e);
            case "table" -> table(e);
            default -> BLOCKS.contains(name) ? "\n\n" + children(e) + "\n\n" : children(e);
        };
    }

    /** 首尾的空格挪到标记外面，{@code a<b> x </b>b} 变成 {@code a **x** b}（同 turndown）。 */
    private static String wrap(String mark, String inner) {
        String text = oneLine(inner);
        return text.isEmpty() ? inner : outerSpace(inner, mark + text + mark);
    }

    private static String outerSpace(String inner, String marked) {
        String flat = inner.replace('\n', ' ');
        return (flat.startsWith(" ") ? " " : "") + marked + (flat.endsWith(" ") ? " " : "");
    }

    private static String code(String text) {
        String fence = text.contains("`") ? "``" : "`";
        String padded = fence.length() > 1 ? " " + text + " " : text;
        return text.isBlank() ? "" : fence + padded + fence;
    }

    /** 代码块原样保留空白；语言取 {@code class="language-x"}。 */
    private static String pre(Element e) {
        String code = e.wholeText();
        if (code.startsWith("\n")) {
            code = code.substring(1);
        }
        code = PRIVATE.matcher(code.stripTrailing()).replaceAll(" ");
        String language = "";
        Element inner = e.selectFirst("code[class]");
        for (String cls : (inner == null ? e : inner).classNames()) {
            if (cls.startsWith("language-") || cls.startsWith("lang-")) {
                language = cls.substring(cls.indexOf('-') + 1);
            }
        }
        String fence = code.contains("```") ? "````" : "```";
        String protectedCode = code.replace(' ', SPACE).replace('\t', TAB).replace('\n', NEWLINE);
        return "\n\n" + fence + language + NEWLINE + protectedCode + NEWLINE + fence + "\n\n";
    }

    private static String link(Element e) {
        String inner = children(e);
        String text = oneLine(inner);
        String href = e.absUrl("href");
        if (text.isEmpty() || href.isEmpty() || href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
            return inner;
        }
        return outerSpace(inner, "[" + text + "](" + url(href) + ")");
    }

    /** {@code data:} 图片往往很长，又没有给模型看的意义，只留说明文字。 */
    private static String image(Element e) {
        String alt = oneLine(e.attr("alt"));
        String src = e.absUrl("src");
        if (src.isEmpty() || src.toLowerCase(Locale.ROOT).startsWith("data:")) {
            return alt;
        }
        return "![" + alt.replace("]", "\\]") + "](" + url(src) + ")";
    }

    private static String url(String url) {
        return url.replace(" ", "%20").replace("(", "%28").replace(")", "%29");
    }

    /** 无序列表用 {@code - }，有序列表用编号；项里的后续行缩进到标记之后，嵌套列表因此也对齐。 */
    private static String list(Element e) {
        boolean ordered = e.normalName().equals("ol");
        int number = 1;
        if (ordered && e.hasAttr("start")) {
            try {
                number = Integer.parseInt(e.attr("start").strip());
            } catch (NumberFormatException ignored) {
                // 写错了就从 1 开始
            }
        }
        StringBuilder out = new StringBuilder("\n\n");
        for (Element item : e.children()) {
            if (!item.normalName().equals("li")) {
                continue;
            }
            String marker = ordered ? (number++) + ". " : "- ";
            String content = tidy(children(item)).replaceAll("\n\n+", "\n");
            out.append(prefixed(content, marker, String.valueOf(SPACE).repeat(marker.length()))).append('\n');
        }
        return out.append('\n').toString();
    }

    private static String quote(Element e) {
        String content = tidy(children(e));
        StringBuilder out = new StringBuilder("\n\n");
        for (String line : content.split("\n", -1)) {
            out.append(line.isEmpty() ? ">" : "> " + line).append('\n');
        }
        return out.append('\n').toString();
    }

    private static String prefixed(String content, String first, String rest) {
        StringBuilder out = new StringBuilder();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(i == 0 ? first : lines[i].isEmpty() ? "" : rest).append(lines[i]);
        }
        return out.toString();
    }

    /** GFM 管道表：第一行当表头，单元格里的 {@code |} 转义，换行换成空格。只看这张表自己的行，嵌套的表压成单元格里的文字。 */
    private static String table(Element e) {
        List<List<String>> rows = new ArrayList<>();
        for (Element row : rows(e)) {
            List<String> cells = new ArrayList<>();
            for (Element cell : row.children()) {
                if (cell.normalName().equals("td") || cell.normalName().equals("th")) {
                    cells.add(oneLine(tidy(children(cell))).replace("|", "\\|"));
                }
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return "";
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        StringBuilder out = new StringBuilder("\n\n");
        for (int r = 0; r < rows.size(); r++) {
            out.append(row(rows.get(r), columns));
            if (r == 0) {
                out.append(row(java.util.Collections.nCopies(columns, "---"), columns));
            }
        }
        return out.append('\n').toString();
    }

    private static List<Element> rows(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element child : table.children()) {
            switch (child.normalName()) {
                case "tr" -> rows.add(child);
                case "thead", "tbody", "tfoot" -> child.children().stream().filter(row -> row.normalName().equals("tr")).forEach(rows::add);
                default -> { }
            }
        }
        return rows;
    }

    private static String row(List<String> cells, int columns) {
        StringBuilder out = new StringBuilder("|");
        for (int c = 0; c < columns; c++) {
            String cell = c < cells.size() ? cells.get(c) : "";
            out.append(' ').append(cell).append(cell.isEmpty() ? "" : " ").append('|');
        }
        return out.append('\n').toString();
    }

    private static String oneLine(String text) {
        return text.replace('\n', ' ').replaceAll(" {2,}", " ").strip();
    }

    /** 每行压空白、去掉行首行尾的空格，连续空行压成一个。私用区字符（缩进、代码）不受影响。 */
    private static String tidy(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            out.append(line.replaceAll("[ \t]+", " ").strip()).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }
}
