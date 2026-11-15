package tantalum.shell.memory.capture;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.memory.CredentialGuard;
import tantalum.shell.memory.DailyNotes;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.trace.RunRow;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.TextBlock;
import tantalum.value.state.EndReason;

/**
 * 每轮结束后自动记笔记（memory-capture 需求 1、2、4、5）：运行完成时把这一轮排进一个有界队列，几个后台线程逐个处理——
 * 摘出这一轮、让模型写要点、去掉含密钥的要点、写进当天的笔记。不碰 journal、不拖慢对话；同一轮只记一次；失败最多重试两次。
 */
public final class NoteTaker implements AutoCloseable {

    /** 这一轮的轨迹记录。 */
    @FunctionalInterface
    public interface Records {
        List<TraceRecord> of(Principal user, SessionId session, RunId run);
    }

    /** 记笔记用的模型：哪个客户端、哪个模型。 */
    public record Writer(ModelClient client, ModelChoice choice) {}

    private static final Logger LOG = LoggerFactory.getLogger(NoteTaker.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final long[] BACKOFF_MILLIS = {5_000, 20_000};

    private final CaptureSettings settings;
    private final Predicate<Principal> userWants;
    private final Function<Principal, DailyNotes> notes;
    private final Records records;
    private final Function<SessionId, String> titles;
    private final Writer writer;
    private final Clock clock;
    private final ZoneId zone;
    private final ArrayBlockingQueue<RunRow> queue;
    private final List<Thread> workers = new ArrayList<>();
    private final long[] backoff;
    private volatile boolean closed;

    public NoteTaker(CaptureSettings settings, Predicate<Principal> userWants, Function<Principal, DailyNotes> notes, Records records,
                     Function<SessionId, String> titles, Writer writer, Clock clock, ZoneId zone) {
        this(settings, userWants, notes, records, titles, writer, clock, zone, BACKOFF_MILLIS);
    }

    NoteTaker(CaptureSettings settings, Predicate<Principal> userWants, Function<Principal, DailyNotes> notes, Records records,
              Function<SessionId, String> titles, Writer writer, Clock clock, ZoneId zone, long[] backoff) {
        this.settings = settings;
        this.userWants = userWants;
        this.notes = notes;
        this.records = records;
        this.titles = titles;
        this.writer = writer;
        this.clock = clock;
        this.zone = zone;
        this.backoff = backoff.clone();
        this.queue = new ArrayBlockingQueue<>(settings.queue());
        if (settings.enabled() && writer != null) {
            for (int i = 0; i < settings.workers(); i++) {
                workers.add(Thread.ofPlatform().daemon().name("memory-notes-" + i).start(this::work));
            }
        }
    }

    /** 一次运行结束（运行表的最终一行）。不阻塞；排不下时丢掉最旧的。 */
    public void offer(RunRow row) {
        if (closed || !settings.enabled() || writer == null || !worthNoting(row)) {
            return;
        }
        while (!queue.offer(row)) {
            RunRow dropped = queue.poll();
            if (dropped != null) {
                LOG.warn("memory notes are behind; dropped run {}", dropped.run().value());
            }
        }
    }

    static boolean worthNoting(RunRow row) {
        return row.end() != null && row.endReason() == EndReason.COMPLETED
                && row.trigger() != tantalum.shell.trace.TraceRecord.RunHead.Trigger.MANUAL_COMPACTION;
    }

    /** 测试用：现在排着几个。 */
    int pending() {
        return queue.size();
    }

    private void work() {
        while (!closed) {
            RunRow row;
            try {
                row = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException stopping) {
                return;
            }
            if (row != null) {
                note(row);
            }
        }
    }

    /** 记一轮；返回写没写。测试直接调。 */
    boolean note(RunRow row) {
        try {
            if (!userWants.test(row.principal())) {
                return false;
            }
            String day = LocalDate.ofInstant(row.end(), zone).toString();
            DailyNotes daily = notes.apply(row.principal());
            if (daily.noted(day, row.session().value(), row.run().value())) {
                return false;
            }
            String excerpt = RunExcerpt.of(records.of(row.principal(), row.session(), row.run()), settings.maxInputChars());
            if (excerpt.isBlank()) {
                return false;
            }
            String title = titles.apply(row.session());
            String reply = ask(row, NotePrompt.of(title, day, excerpt, settings.maxOutputTokens(), writer.choice()));
            if (reply == null) {
                return false;
            }
            List<String> kept = new ArrayList<>();
            int dropped = 0;
            for (String bullet : NotePrompt.bullets(reply)) {
                if (CredentialGuard.detect(bullet).isPresent()) {
                    dropped++;
                } else {
                    kept.add(bullet);
                }
            }
            if (dropped > 0) {
                LOG.warn("memory note for run {}: dropped {} bullet(s) that looked like credentials", row.run().value(), dropped);
            }
            if (kept.isEmpty()) {
                return false;
            }
            String heading = TIME.format(row.start().atZone(zone)) + (row.turn() == null ? "" : " · #" + row.turn());
            return daily.append(day, row.session().value(), title == null ? "" : title, row.run().value(), heading, String.join("\n", kept));
        } catch (RuntimeException failure) {
            LOG.warn("memory note for run {} failed: {}", row.run().value(), failure.toString());
            return false;
        }
    }

    /** 调模型，失败按 {@link #BACKOFF_MILLIS} 重试；最后还失败就放弃（返回 null）。 */
    private String ask(RunRow row, tantalum.value.model.Prompt prompt) {
        ChunkListener quiet = new ChunkListener() {
            @Override
            public void onText(String text) {
            }

            @Override
            public void onReasoning(String text) {
            }
        };
        for (int attempt = 0; attempt <= backoff.length; attempt++) {
            if (closed) {
                return null;
            }
            ModelOutcome outcome = writer.client().call(prompt, quiet);
            if (outcome instanceof ModelOutcome.Replied replied) {
                StringBuilder text = new StringBuilder();
                for (ContentBlock block : replied.content()) {
                    if (block instanceof TextBlock part) {
                        text.append(part.text());
                    }
                }
                LOG.info("memory note for {} run {}: {}/{} tokens via {}/{}", row.principal().value(), row.run().value(),
                        replied.usage() == null ? "?" : replied.usage().promptTotal(), replied.usage() == null ? "?" : replied.usage().output(),
                        writer.choice().route(), writer.choice().model());
                return text.toString();
            }
            if (attempt < backoff.length) {
                try {
                    Thread.sleep(backoff[attempt]);
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        LOG.warn("memory note for run {} gave up after {} attempts", row.run().value(), backoff.length + 1);
        return null;
    }

    @Override
    public void close() {
        closed = true;
        workers.forEach(Thread::interrupt);
    }
}
