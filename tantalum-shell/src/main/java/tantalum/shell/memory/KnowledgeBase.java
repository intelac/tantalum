package tantalum.shell.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第四层：管理员维护的只读目录，里面的 {@code *.md} 按文件名顺序拼成一段进所有用户的 system。
 * 按目录里文件的最大修改时间缓存；总字节超限时按文件名顺序截断并记警告，运行不失败。
 */
public final class KnowledgeBase {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBase.class);

    private final Optional<Path> dir;
    private final int maxBytes;
    private volatile Snapshot cached;
    private boolean warnedMissing;

    private record Snapshot(FileTime stamp, int fileCount, String text) {}

    public KnowledgeBase(Optional<Path> dir, int maxBytes) {
        this.dir = dir;
        this.maxBytes = maxBytes;
    }

    public static KnowledgeBase none() {
        return new KnowledgeBase(Optional.empty(), 1);
    }

    /** 共享知识正文；没有配置、目录不存在或为空时返回空串。 */
    public String text() {
        if (dir.isEmpty()) {
            return "";
        }
        Path root = dir.get();
        if (!Files.isDirectory(root)) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOG.warn("knowledge dir {} does not exist; the shared knowledge layer is empty", root);
            }
            return "";
        }
        try {
            List<Path> files = markdownFiles(root);
            FileTime stamp = FileTime.fromMillis(0);
            for (Path file : files) {
                FileTime modified = Files.getLastModifiedTime(file);
                if (modified.compareTo(stamp) > 0) {
                    stamp = modified;
                }
            }
            Snapshot current = cached;
            if (current != null && current.stamp().equals(stamp) && current.fileCount() == files.size()) {
                return current.text();
            }
            String text = render(files);
            cached = new Snapshot(stamp, files.size(), text);
            return text;
        } catch (IOException failure) {
            LOG.warn("cannot read knowledge dir {}: {}", root, failure.toString());
            return "";
        }
    }

    private static List<Path> markdownFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> listing = Files.list(root)) {
            listing.filter(file -> file.getFileName().toString().endsWith(".md"))
                    .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    private String render(List<Path> files) throws IOException {
        StringBuilder text = new StringBuilder();
        int used = 0;
        for (Path file : files) {
            String body = "### " + file.getFileName() + "\n\n" + Files.readString(file).strip() + "\n\n";
            int bytes = body.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) {
                LOG.warn("knowledge dir exceeds {} bytes; files from {} on are left out", maxBytes, file.getFileName());
                break;
            }
            text.append(body);
            used += bytes;
        }
        return text.toString().strip();
    }
}
