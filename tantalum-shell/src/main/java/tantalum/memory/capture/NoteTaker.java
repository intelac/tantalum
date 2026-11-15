package tantalum.memory.capture;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.memory.CredentialGuard;
import tantalum.memory.DailyNotes;
import tantalum.memory.MemoryTools;
import tantalum.shell.plugin.Configurable;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.PluginInfo;
import tantalum.shell.plugin.RunFinishedListener;
import tantalum.value.id.Principal;
import tantalum.value.text.LocalText;

/**
 * 每轮结束后自动记笔记（memory-capture 需求 1、2、4、5；memory-plugin：平台的“运行结束后”插件点）：平台通知一轮结束时排进一个有界队列，
 * 几个后台线程逐个处理——让模型按平台给的摘录写要点、去掉含密钥的要点、写进当天的笔记。不碰 journal、不拖慢对话；同一轮只记一次；
 * 失败最多重试两次。自己排队，免得重试的等待占住平台给所有插件共用的通知线程。
 */
public final class NoteTaker implements RunFinishedListener, Configurable, PluginInfo, AutoCloseable {

    /** 记笔记用的模型调用：system、user、输出上限 → 回答的文字；失败为空。 */
    @FunctionalInterface
    public interface Writer {
        Optional<String> write(String system, String user, int maxTokens);
    }

    /** 插件页上的参数：自动记下。 */
    public static final String CAPTURE = "capture";

    private static final Logger LOG = LoggerFactory.getLogger(NoteTaker.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final long[] BACKOFF_MILLIS = {5_000, 20_000};

    private final CaptureSettings settings;
    private final Predicate<Principal> userWants;
    private final Function<Principal, DailyNotes> notes;
    private final Writer writer;
    private final ZoneId zone;
    private final ArrayBlockingQueue<FinishedRun> queue;
    private final List<Thread> workers = new ArrayList<>();
    private final long[] backoff;
    private volatile boolean closed;

    public NoteTaker(CaptureSettings settings, Predicate<Principal> userWants, Function<Principal, DailyNotes> notes, Writer writer,
                     ZoneId zone) {
        this(settings, userWants, notes, writer, zone, BACKOFF_MILLIS);
    }

    NoteTaker(CaptureSettings settings, Predicate<Principal> userWants, Function<Principal, DailyNotes> notes, Writer writer, ZoneId zone,
              long[] backoff) {
        this.settings = settings;
        this.userWants = userWants;
        this.notes = notes;
        this.writer = writer;
        this.zone = zone;
        this.backoff = backoff.clone();
        this.queue = new ArrayBlockingQueue<>(settings.queue());
        if (settings.enabled()) {
            for (int i = 0; i < settings.workers(); i++) {
                workers.add(Thread.ofPlatform().daemon().name("memory-notes-" + i).start(this::work));
            }
        }
    }

    @Override
    public String plugin() {
        return MemoryTools.PLUGIN;
    }

    @Override
    public List<Param> params() {
        return List.of(Param.toggle(CAPTURE, true)
                .label(LocalText.en("Take notes automatically").zh("自动记下").ja("自動でメモ"))
                .hint(LocalText.en("After each turn, note what is worth remembering in today's notes.")
                        .zh("每轮结束后，把值得记住的写进当天的笔记。")
                        .ja("毎回の終わりに、覚えておく価値のあることを今日のメモに書きます。")));
    }

    @Override
    public LocalText description() {
        return LocalText.en("Long-term memory, daily notes, and automatic note-taking.")
                .zh("长期记忆、每天的笔记和自动记下。")
                .ja("長期記憶、毎日のメモ、自動メモ。");
    }

    /** 平台通知一轮结束。不阻塞；排不下时丢掉最旧的。 */
    @Override
    public void runFinished(FinishedRun run) {
        if (closed || !settings.enabled() || !worthNoting(run)) {
            return;
        }
        while (!queue.offer(run)) {
            FinishedRun dropped = queue.poll();
            if (dropped != null) {
                LOG.warn("memory notes are behind; dropped run {}", dropped.run().value());
            }
        }
    }

    static boolean worthNoting(FinishedRun run) {
        return run.end() != null && "COMPLETED".equals(run.endReason()) && !run.manual();
    }

    /** 测试用：现在排着几个。 */
    int pending() {
        return queue.size();
    }

    private void work() {
        while (!closed) {
            FinishedRun run;
            try {
                run = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException stopping) {
                return;
            }
            if (run != null) {
                note(run);
            }
        }
    }

    /** 记一轮；返回写没写。测试直接调。 */
    boolean note(FinishedRun run) {
        try {
            if (!userWants.test(run.user())) {
                return false;
            }
            String day = LocalDate.ofInstant(run.end(), zone).toString();
            DailyNotes daily = notes.apply(run.user());
            if (daily.noted(day, run.session().value(), run.run().value())) {
                return false;
            }
            String excerpt = capped(run.excerpt() == null ? "" : run.excerpt(), settings.maxInputChars());
            if (excerpt.isBlank()) {
                return false;
            }
            String title = run.title();
            String reply = ask(run, NotePrompt.user(title, day, excerpt));
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
                LOG.warn("memory note for run {}: dropped {} bullet(s) that looked like credentials", run.run().value(), dropped);
            }
            if (kept.isEmpty()) {
                return false;
            }
            String heading = TIME.format(run.start().atZone(zone)) + (run.turn() == null ? "" : " · #" + run.turn());
            return daily.append(day, run.session().value(), title == null ? "" : title, run.run().value(), heading, String.join("\n", kept));
        } catch (RuntimeException failure) {
            LOG.warn("memory note for run {} failed: {}", run.run().value(), failure.toString());
            return false;
        }
    }

    /** 平台的摘录比配置的上限长时，留头留尾。 */
    static String capped(String excerpt, int maxChars) {
        if (excerpt.length() <= maxChars) {
            return excerpt;
        }
        int head = maxChars * 2 / 3;
        int tail = maxChars - head;
        return excerpt.substring(0, head) + "\n…\n" + excerpt.substring(excerpt.length() - tail);
    }

    /** 调模型，失败按 {@link #BACKOFF_MILLIS} 重试；最后还失败就放弃（返回 null）。 */
    private String ask(FinishedRun run, String user) {
        for (int attempt = 0; attempt <= backoff.length; attempt++) {
            if (closed) {
                return null;
            }
            Optional<String> reply = writer.write(NotePrompt.system(), user, settings.maxOutputTokens());
            if (reply.isPresent()) {
                return reply.get();
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
        LOG.warn("memory note for run {} gave up after {} attempts", run.run().value(), backoff.length + 1);
        return null;
    }

    @Override
    public void close() {
        closed = true;
        workers.forEach(Thread::interrupt);
    }
}
