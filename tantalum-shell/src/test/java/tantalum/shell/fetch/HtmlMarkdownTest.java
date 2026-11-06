package tantalum.shell.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/** W4、需求 1.1：不显示的内容不进正文；标题、链接、列表、代码块、表格照 DSH 的 turndown 规则转。 */
class HtmlMarkdownTest {

    private static String md(String body) {
        return HtmlMarkdown.of(Jsoup.parse("<html><head><title>t</title><style>.x{}</style></head><body>" + body + "</body></html>",
                "https://site.example/docs/page.html"));
    }

    @Test
    void hiddenContentNeverAppears() {
        String markdown = md("<p>shown</p>"
                + "<script>var leak1 = 1;</script><style>.leak2{}</style><noscript>leak3</noscript><template><p>leak4</p></template>"
                + "<iframe src='x'>leak5</iframe><object>leak6</object><embed src='y'>"
                + "<div hidden>leak7</div><span aria-hidden=\"true\">leak8</span><input type=hidden value=leak9>"
                + "<p style=\"color: red; display : none\">leak10</p><p style='visibility:hidden'>leak11</p>"
                + "<p style='visibility: collapse !important'>leak12</p><p style='display:block'>also shown</p>");
        assertEquals("shown\n\nalso shown", markdown);
        assertFalse(markdown.contains("leak"), markdown);
    }

    @Test
    void headingsAndInlineMarks() {
        assertEquals("# Title\n\n## Sub _part_\n\nSome **bold** and _it_, then `x = 1`.",
                md("<h1>Title</h1><h2>Sub <em>part</em></h2><p>Some<b> bold </b>and <i>it</i>, then <code>x = 1</code>.</p>"));
    }

    @Test
    void linksAndImagesBecomeAbsolute() {
        assertEquals("See [the guide](https://site.example/docs/guide.html), [root](https://site.example/a%20b) and "
                        + "![a chart](https://cdn.example/c.png).\n\nclick",
                md("<p>See <a href='guide.html'>the guide</a>, <a href='/a b'>root</a> and "
                        + "<img src='//cdn.example/c.png' alt='a chart'>.</p><p><a href='javascript:void(0)'>click</a>"
                        + "<a href='/icon'><img src='data:image/png;base64,AAAA'></a></p>"));
    }

    @Test
    void nestedListsAreIndentedUnderTheirItem() {
        assertEquals("- one\n- two\n  - two a\n  - two b\n- three\n\n3. third\n4. fourth",
                md("<ul><li>one</li><li>two<ul><li>two a</li><li>two b</li></ul></li><li>three</li></ul>"
                        + "<ol start='3'><li>third</li><li>fourth</li></ol>"));
    }

    @Test
    void codeBlocksKeepTheirWhitespace() {
        String markdown = md("<p>Run:</p><pre><code class='language-java'>if (x) {\n    call(a,  b);\n\n\n}\n</code></pre><p>done</p>");
        assertEquals("Run:\n\n```java\nif (x) {\n    call(a,  b);\n\n\n}\n```\n\ndone", markdown);
    }

    @Test
    void tablesBecomePipeTablesWithEscapedPipes() {
        assertEquals("| Name | Value |\n| --- | --- |\n| a\\|b | 1 |\n| c | |",
                md("<table><thead><tr><th>Name</th><th>Value</th></tr></thead>"
                        + "<tbody><tr><td>a|b</td><td>\n 1 \n</td></tr><tr><td>c</td></tr></tbody></table>"));
    }

    @Test
    void blankLinesCollapseAndQuotesArePrefixed() {
        String markdown = md("<div><p>a</p><br><br><br><p>b</p></div><blockquote><p>q1</p><p>q2</p></blockquote>");
        assertEquals("a\n\nb\n\n> q1\n>\n> q2", markdown);
        assertTrue(!markdown.contains("\n\n\n"), markdown);
    }
}
