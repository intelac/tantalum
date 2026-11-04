package tantalum.shell.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.ImageNormalizer;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionFiles;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.journal.StoreFormat;
import tantalum.shell.journal.StoreLock;
import tantalum.shell.loop.CleanroomRing;
import tantalum.shell.loop.UserLane;
import tantalum.shell.store.Backfill;
import tantalum.shell.store.IndexCatchUp;
import tantalum.shell.store.RecordReplay;
import tantalum.shell.store.IndexWriter;
import tantalum.shell.store.SessionIndex;
import tantalum.shell.store.UsageLedger;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.PrincipalState;

/**
 * 数据目录：锁、格式、会话文件、索引和附件，以及读它们共用的对象。
 * 按顺序打开，中途失败时关掉已打开的，免得嵌入方重试时一直撞上“store is locked”；关闭顺序和打开相反。
 */
final class Storage implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Storage.class);
    private static final int MAX_OPEN_FILES = 256;

    final SessionPaths paths;
    final SessionFileReader reader;
    final StoreLock lock;
    final SessionIndex index;
    final IndexWriter indexWriter;
    final UsageLedger usage;
    final SessionFiles files;
    final AttachmentStore attachments;
    final RecordReplay records;

    private Storage(Path dataDir, EventCodec codec, TantalumProperties.Index throttle, AttachmentLimits attachmentLimits,
                    Deque<AutoCloseable> opened) throws IOException {
        paths = new SessionPaths(dataDir);
        reader = new SessionFileReader(codec);
        lock = StoreLock.acquire(dataDir);
        opened.push(lock);
        StoreFormat.check(dataDir);
        try {
            index = SessionIndex.open(dataDir);
        } catch (SQLException failure) {
            throw new IOException("cannot open index", failure);
        }
        opened.push(index);
        indexWriter = new IndexWriter(index, throttle.flushEvents(), throttle.flushInterval());
        opened.push(indexWriter);
        usage = new UsageLedger(index, indexWriter);
        files = new SessionFiles(paths, codec, MAX_OPEN_FILES, indexWriter::onDurable);
        opened.push(files);
        attachments = new AttachmentStore(dataDir, attachmentLimits, new ImageNormalizer(attachmentLimits));
        records = new RecordReplay(paths, reader);
    }

    static Storage open(Path dataDir, EventCodec codec, TantalumProperties.Index throttle, AttachmentLimits attachmentLimits)
            throws IOException {
        Deque<AutoCloseable> opened = new ArrayDeque<>();
        try {
            return new Storage(dataDir, codec, throttle, attachmentLimits, opened);
        } catch (IOException | RuntimeException failure) {
            opened.forEach(Storage::closeQuietly);
            throw failure;
        }
    }

    /**
     * 先补写上次崩溃留下的、没有结果行的事件（record-results R5），再让索引追上会话文件，返回有未结束运行的会话。
     * 补不上就不知道哪些运行要收尾，拒绝启动；索引可以删掉重建。
     */
    List<SessionIndex.Session> catchUp(Kernel kernel) {
        try {
            Backfill.run(index, paths, records, files, kernel);
            IndexCatchUp.run(index, paths, reader);
            return index.unfinished();
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("the index cannot catch up with the session files (" + failure
                    + "); delete " + SessionIndex.FILE + " in the data directory and start again to rebuild it", failure);
        }
    }

    /**
     * 用户通道载入冷会话和用户用量的来源：按结果行折叠，不调内核（record-results）。读完文件的 memo 交给写入方，
     * 之后的结果行能引用文件里已有的内容。
     */
    UserLane.Loader loader() {
        return new UserLane.Loader() {
            @Override
            public Optional<CleanroomRing.Loaded> load(Principal principal, SessionId session, long beforeSeq) throws Exception {
                Optional<RecordReplay.Loaded> folded = records.load(principal, session, beforeSeq);
                folded.ifPresent(loaded -> files.adopt(principal, session, loaded.memo()));
                return folded.map(loaded -> new CleanroomRing.Loaded(loaded.slice()));
            }

            @Override
            public PrincipalState usage(Principal principal, long beforeSeq) throws Exception {
                return new PrincipalState(principal, usage.before(principal, beforeSeq));
            }
        };
    }

    /** 提交去重时内存里没有的请求 ID 查这里。查不到时当作新提交：可用性优先，日志留痕。 */
    Optional<SubmissionId> findSubmission(Principal principal, RequestId request) {
        try {
            return index.submission(principal, request);
        } catch (SQLException failure) {
            LOG.warn("index lookup failed, treating request as new: {}", failure.toString());
            return Optional.empty();
        }
    }

    /** 新会话 ID 只属于第一个认领的用户。返回 HTTP 状态。 */
    int claim(Principal principal, SessionId session) {
        try {
            return index.claim(session, principal) ? 200 : 404;
        } catch (SQLException failure) {
            LOG.error("cannot record session ownership", failure);
            return 503;
        }
    }

    @Override
    public void close() throws IOException {
        Shutdown.run(List.of(
                new Shutdown.Step("flush index", indexWriter),
                new Shutdown.Step("close index", index),
                new Shutdown.Step("close session files", files),
                new Shutdown.Step("release lock", lock)));
    }

    /** 打开失败后的清理只记日志，抛出的是打开时的那个错误。 */
    static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception failure) {
            LOG.warn("cleanup after failed start: {}", failure.toString());
        }
    }
}
