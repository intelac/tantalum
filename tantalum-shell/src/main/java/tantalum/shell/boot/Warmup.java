package tantalum.shell.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.store.RecordReplay;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tools.jackson.databind.JsonNode;

/**
 * 启动后预热读会话文件和还原的代码（one-line-journal J9）：服务刚启动时第一次打开长会话慢，主要是这些代码还没被 JIT 编译。
 * 把 jar 里的一个小会话（工具调用、压缩、工具变化都有）复制到临时目录，用 {@link RecordReplay} 折叠几遍，然后删掉。
 * 不碰数据目录、不进索引、不占名额；失败只记日志。文件由测试 {@code WarmupFileTest} 生成，格式变了那个测试会失败。
 */
public final class Warmup {

    private static final Logger LOG = LoggerFactory.getLogger(Warmup.class);
    static final String RESOURCE = "/warmup/session.jsonl";
    private static final int ROUNDS = 10;

    private Warmup() {}

    /** 在后台虚拟线程里预热。 */
    static void start(EventCodec codec) {
        Thread.ofVirtual().name("warmup").start(() -> {
            try {
                long started = System.nanoTime();
                int items = run(codec);
                LOG.info("warmup done in {} ms: a session of {} conversation items read {} times", (System.nanoTime() - started) / 1_000_000, items, ROUNDS);
            } catch (Exception | Error failure) {
                LOG.warn("warmup failed; the first session opened after startup is slower", failure);
            }
        });
    }

    /** 折叠内置的小会话几遍，返回它的对话条目数。 */
    public static int run(EventCodec codec) throws IOException {
        Path dir = Files.createTempDirectory("tantalum-warmup-");
        try {
            byte[] content;
            try (InputStream in = Warmup.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IOException("missing " + RESOURCE);
                }
                content = in.readAllBytes();
            }
            String header = new String(content, 0, indexOf(content, (byte) '\n'), StandardCharsets.UTF_8);
            JsonNode identity = codec.mapper().readTree(header);
            Principal principal = new Principal(identity.get("principal").asString());
            SessionId session = new SessionId(identity.get("session").asString());
            SessionPaths paths = new SessionPaths(dir);
            Path file = paths.sessionFile(principal, session);
            Files.createDirectories(file.getParent());
            Files.write(file, content);
            RecordReplay replay = new RecordReplay(paths, new SessionFileReader(codec));
            int items = 0;
            for (int i = 0; i < ROUNDS; i++) {
                RecordReplay.Loaded loaded = replay.load(principal, session, Long.MAX_VALUE)
                        .orElseThrow(() -> new IOException("the warmup session is empty"));
                items = loaded.slice().transcript().items().size();
            }
            return items;
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static int indexOf(byte[] bytes, byte value) throws IOException {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        throw new IOException(RESOURCE + " has no header line");
    }
}
