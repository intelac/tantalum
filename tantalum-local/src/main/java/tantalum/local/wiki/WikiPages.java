package tantalum.local.wiki;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.fs.UserDir;
import tantalum.local.wire.DirState;
import tantalum.local.wire.WikiList;

/**
 * 用户电脑上 wiki 目录里有哪些页（skills-wiki 需求 6）：目录下所有 {@code .md}，含子目录，跳过 {@code .} 开头的目录和文件、
 * {@code node_modules}；不跟符号链接，所以走不出这个目录。只列路径和标题，页的内容模型用现有的读文件工具按需读。
 */
public final class WikiPages {

    static final String INDEX = "index.md";
    /** 找标题只看文件开头这么多。 */
    private static final int TITLE_BYTES = 4096;
    private static final Pattern TITLE = Pattern.compile("(?m)^# +(\\S.*)$");
    private static final Pattern BLANKS = Pattern.compile("\\s+");

    private WikiPages() {}

    public static WikiList list(AllowedDirs dirs, String dir) {
        return list(dirs, dir, WikiList.MAX_FILES);
    }

    static WikiList list(AllowedDirs dirs, String dir, int maxFiles) {
        UserDir located = UserDir.locate(dirs, dir);
        if (located.state() != DirState.OK) {
            return WikiList.unusable(located.state());
        }
        Path root = located.path();
        List<String> paths = new ArrayList<>();
        boolean[] rootFailed = {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private int seen;

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    String name = directory.equals(root) ? "" : directory.getFileName().toString();
                    return name.startsWith(".") || name.equals("node_modules") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    // 目录太大时到此为止，免得一次列目录耗掉整个等待时间
                    if (++seen > maxFiles) {
                        return FileVisitResult.TERMINATE;
                    }
                    String name = file.getFileName().toString();
                    if (attributes.isRegularFile() && !name.startsWith(".") && name.toLowerCase(Locale.ROOT).endsWith(".md")) {
                        paths.add(relative(root, file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    if (file.equals(root)) {
                        rootFailed[0] = true;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            return WikiList.unusable(DirState.UNREADABLE);
        }
        if (rootFailed[0]) {
            return WikiList.unusable(DirState.UNREADABLE);
        }
        String index = paths.remove(INDEX) ? head(root.resolve(INDEX), WikiList.MAX_INDEX_BYTES) : null;
        paths.sort(null);
        List<WikiList.Page> pages = new ArrayList<>();
        for (String path : paths.subList(0, Math.min(paths.size(), WikiList.MAX_PAGES))) {
            pages.add(new WikiList.Page(path, title(root.resolve(path))));
        }
        return new WikiList(root.toString(), DirState.OK, pages, paths.size(), index);
    }

    /** 相对路径，分隔符一律 {@code /}，Windows 上列出来的和别的系统一样。 */
    private static String relative(Path root, Path file) {
        List<String> parts = new ArrayList<>();
        for (Path part : root.relativize(file)) {
            parts.add(part.toString());
        }
        return String.join("/", parts);
    }

    /** 第一个一级标题；没有、读不了时为 null。 */
    private static String title(Path file) {
        String head = head(file, TITLE_BYTES);
        if (head == null) {
            return null;
        }
        Matcher matcher = TITLE.matcher(head.replace("\r\n", "\n"));
        if (!matcher.find()) {
            return null;
        }
        String title = BLANKS.matcher(matcher.group(1).strip()).replaceAll(" ");
        if (title.codePointCount(0, title.length()) > WikiList.MAX_TITLE) {
            title = title.substring(0, title.offsetByCodePoints(0, WikiList.MAX_TITLE));
        }
        return title;
    }

    /** 文件开头最多 {@code max} 字节，按 UTF-8 读；正好切在一个字中间时去掉那半个字。 */
    private static String head(Path file, int max) {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(max + 1);
        } catch (IOException failure) {
            return null;
        }
        int length = Math.min(bytes.length, max);
        if (bytes.length > max) {
            while (length > 0 && (bytes[length] & 0xC0) == 0x80) {
                length--;
            }
        }
        String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
