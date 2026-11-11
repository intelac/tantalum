package tantalum.local.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.wire.DirState;
import tantalum.local.wire.WikiList;

/** skills-wiki 需求 6、9.1：列 wiki 目录里的页，只有路径和标题；走不出这个目录。 */
class WikiPagesTest {

    @TempDir
    Path temp;

    private Path root;
    private Path wiki;
    private AllowedDirs dirs;

    @BeforeEach
    void setUp() throws Exception {
        temp = temp.toRealPath();
        root = Files.createDirectory(temp.resolve("root"));
        wiki = Files.createDirectory(root.resolve("wiki"));
        dirs = new AllowedDirs(List.of(root));
    }

    private void page(String path, String text) throws Exception {
        Path file = wiki.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private WikiList list() {
        return WikiPages.list(dirs, wiki.toString());
    }

    @Test
    void listsMarkdownPagesRecursivelySortedWithTitles() throws Exception {
        page("setup.md", "# Setting  up\n\nText\n");
        page("guides/deploy.md", "Intro line\r\n# Deploy guide\r\n## Sub\r\n");
        page("guides/deep/more.MD", "no heading here\n## only second level\n");
        page("a.md", "#NotATitle\n#  \n");
        page("notes.txt", "# Not markdown");
        page("image.png", "x");
        WikiList list = list();
        assertEquals(DirState.OK, list.state());
        assertEquals(wiki.toString(), list.dir());
        assertEquals(List.of(
                new WikiList.Page("a.md", null),
                new WikiList.Page("guides/deep/more.MD", null),
                new WikiList.Page("guides/deploy.md", "Deploy guide"),
                new WikiList.Page("setup.md", "Setting up")), list.pages());
        assertEquals(4, list.total());
        assertNull(list.index());
    }

    @Test
    void skipsHiddenThingsAndNodeModules() throws Exception {
        page("kept.md", "# Kept");
        page(".obsidian/config.md", "# Config");
        page(".draft.md", "# Draft");
        page("node_modules/pkg/README.md", "# Pkg");
        page("sub/.git/x.md", "# Git");
        assertEquals(List.of(new WikiList.Page("kept.md", "Kept")), list().pages());
    }

    /** 需求 6.3：根上的 index.md 整个带回去，不再列在页里；子目录里的 index.md 是普通的页。 */
    @Test
    void theRootIndexIsReturnedWholeAndLeftOutOfThePages() throws Exception {
        page("index.md", "﻿# Team wiki\n\nStart with [setup](setup.md).\n");
        page("setup.md", "# Setup");
        page("sub/index.md", "# Sub index");
        WikiList list = list();
        assertEquals("# Team wiki\n\nStart with [setup](setup.md).\n", list.index());
        assertEquals(List.of("setup.md", "sub/index.md"), list.pages().stream().map(WikiList.Page::path).toList());
        assertEquals(2, list.total());
    }

    @Test
    void aLongIndexIsCutAtACharacterBoundary() throws Exception {
        page("index.md", "a".repeat(WikiList.MAX_INDEX_BYTES - 1) + "字" + "tail");
        String index = list().index();
        assertEquals("a".repeat(WikiList.MAX_INDEX_BYTES - 1), index);
        page("index.md", "a".repeat(WikiList.MAX_INDEX_BYTES - 3) + "字" + "tail");
        assertEquals("a".repeat(WikiList.MAX_INDEX_BYTES - 3) + "字", list().index());
    }

    @Test
    void theTitleIsOnlyLookedForNearTheTopAndIsCut() throws Exception {
        page("late.md", "x".repeat(5000) + "\n# Too late\n");
        page("long.md", "# " + "题".repeat(300) + "\n");
        WikiList list = list();
        assertNull(list.pages().get(0).title());
        assertEquals("题".repeat(WikiList.MAX_TITLE), list.pages().get(1).title());
    }

    /** 需求 6.4：最多列 300 页，总数照实说。 */
    @Test
    void atMost300PagesAreListedButTheTotalIsTheRealCount() throws Exception {
        for (int i = 0; i < WikiList.MAX_PAGES + 5; i++) {
            page(String.format("p%04d.md", i), "# P" + i);
        }
        WikiList list = list();
        assertEquals(WikiList.MAX_PAGES, list.pages().size());
        assertEquals(WikiList.MAX_PAGES + 5, list.total());
        assertEquals("p0000.md", list.pages().getFirst().path());
        assertEquals("p0299.md", list.pages().getLast().path());
    }

    /** 目录太大时看够这么多文件就停，不会把等待时间耗光。 */
    @Test
    void stopsAfterTheFileLimit() throws Exception {
        for (int i = 0; i < 20; i++) {
            page("f" + i + ".md", "# F");
        }
        WikiList list = WikiPages.list(dirs, wiki.toString(), 7);
        assertEquals(DirState.OK, list.state());
        assertEquals(7, list.total());
    }

    @Test
    void doesNotFollowSymlinksOutOfTheDirectory() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.md"), "# SECRET-TITLE");
        Files.createSymbolicLink(wiki.resolve("linked"), outside);
        Files.createSymbolicLink(wiki.resolve("linked.md"), outside.resolve("secret.md"));
        Files.createSymbolicLink(wiki.resolve("index.md"), outside.resolve("secret.md"));
        page("real.md", "# Real");
        WikiList list = list();
        assertEquals(List.of(new WikiList.Page("real.md", "Real")), list.pages());
        assertNull(list.index());
        assertTrue(!list.toString().contains("SECRET"));
    }

    @Test
    void directoryStates() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.write(outside.resolve("x.md"), "# X".getBytes(StandardCharsets.UTF_8));
        assertEquals(WikiList.unusable(DirState.OUTSIDE), WikiPages.list(dirs, outside.toString()));
        assertEquals(WikiList.unusable(DirState.MISSING), WikiPages.list(dirs, root.resolve("nope").toString()));
        assertEquals(WikiList.unusable(DirState.OUTSIDE), WikiPages.list(dirs, Files.createSymbolicLink(root.resolve("out"), outside).toString()));
        WikiList empty = list();
        assertEquals(DirState.OK, empty.state());
        assertEquals(List.of(), empty.pages());
        assertEquals(0, empty.total());
    }
}
