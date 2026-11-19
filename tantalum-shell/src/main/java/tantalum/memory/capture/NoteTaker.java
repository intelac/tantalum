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
import tantalum.memory.DailyNotes;
import tantalum.memory.MemoryTools;
import tantalum.shell.plugin.Configurable;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.PluginInfo;
import tantalum.shell.plugin.RunFinishedListener;
import tantalum.shell.plugin.RunSegment;
import tantalum.value.id.Principal;
import tantalum.value.text.LocalText;

/**
 * 每轮结束后自动记笔记（memory-capture 需求 1、2、4、5；memory-final-plan 第 7、9 节）：平台通知一轮结束时排进一个有界队列，
 * 几个后台线程逐个处理——把你说的话和最后的回答交给模型（工具的输出不给），模型每条笔记附一句原话，{@link NoteFiler} 核对后归档。
 * 不碰 journal、不拖慢对话。处理过的轮次在记忆账上记标记（包括“没什么可记”）；模型失败最多重试两次，还失败就不记标记，
 * 这个人之后有一轮结束时补抓（只补第一次记笔记以后结束的轮次）。自己排队，免得重试的等待占住平台给所有插件共用的通知线程。
 */
public final class NoteTaker implements RunFinishedListener, Configurable, PluginInfo, AutoCloseable {

    /** 记笔记用的模型调用：替谁、system、给模型的文字、输出上限 → 回答的文字；失败为空。 */
    @FunctionalInterface
    public interface Writer {
        Optional<String> write(Principal user, String system, String text, int maxTokens);
    }

    /** 补抓用：这个人某时刻以后结束的轮次，和某一轮的分段（平台的 finishedRuns、runView）。 */
    public interface Catchup {
        List<tantalum.shell.plugin.PluginServices.FinishedRef> finished(Principal user, java.time.Instant since);

        List<RunSegment> view(Principal user, tantalum.value.id.SessionId session, tantalum.value.id.RunId run);

        Catchup NONE = new Catchup() {
            @Override
            public List<tantalum.shell.plugin.PluginServices.FinishedRef> finished(Principal user, java.time.Instant since) {
                return List.of();
            }

            @Override
            public List<RunSegment> view(Principal user, tantalum.value.id.SessionId session, tantalum.value.id.RunId run) {
                return List.of();
            }
        };
    }

    /** 记忆账上的标记：处理过这一轮。 */
    static final String CAPTURED = "captured:";
    /** 记忆账上的标记：从这个时刻起才补抓（第一次记笔记的那一轮开始的时刻）。 */
    static final String CATCHUP_FROM = "catchup-from:";
    /** 补抓最多往回看这么久、每次最多这么多轮、同一个人隔多久查一次。 */
    static final java.time.Duration CATCHUP_WINDOW = java.time.Duration.ofDays(7);
    static final int CATCHUP_LIMIT = 50;
    static final java.time.Duration CATCHUP_EVERY = java.time.Duration.ofMinutes(10);

    /** 插件页上的参数：自动记下。 */
    public static final String CAPTURE = "capture";

    private static final Logger LOG = LoggerFactory.getLogger(NoteTaker.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final long[] BACKOFF_MILLIS = {5_000, 20_000};

    private final CaptureSettings settings;
    private final Predicate<Principal> userWants;
    private final Function<Principal, DailyNotes> notes;
    private final Writer writer;
    /** 每个人的时区：当天卡按它分天，标题里的时刻按它写。 */
    private final Function<Principal, ZoneId> zones;
    private final Function<Principal, NoteFiler> filers;
    private final Catchup catchup;
    private final java.util.Map<Principal, Long> caughtUp = new java.util.concurrent.ConcurrentHashMap<>();
    private final ArrayBlockingQueue<FinishedRun> queue;
    private final List<Thread> workers = new ArrayList<>();
    private final long[] backoff;
    private volatile boolean closed;

    /**
     * {@code filers} 给这个人的归档（写页面、工作记忆、当天卡）；{@code catchup} 补抓用；
     * {@code zones} 是每个人的时区（{@link tantalum.shell.plugin.PluginServices#zone}）。
     */
    public NoteTaker(CaptureSettings settings, Predicate<Principal> userWants, Function<Principal, DailyNotes> notes,
                     Function<Principal, NoteFiler> filers, Writer writer, Catchup catchup, Function<Principal, ZoneId> zones) {
        this(settings, userWants, notes, filers, writer, catchup, zones, BACKOFF_MILLIS);
    }

    NoteTaker(CaptureSettings settings, Predicate<Principal> userWants, Function<Principal, DailyNotes> notes,
              Function<Principal, NoteFiler> filers, Writer writer, Catchup catchup, Function<Principal, ZoneId> zones, long[] backoff) {
        this.filers = filers;
        this.catchup = catchup;
        this.settings = settings;
        this.userWants = userWants;
        this.notes = notes;
        this.writer = writer;
        this.zones = zones;
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

    /** 记忆是个人的：管理员看不到内容，也不能强制打开（memory-final-plan 第 13 节）。 */
    @Override
    public boolean personal() {
        return true;
    }

    /** 平台通知一轮结束。不阻塞；排不下时丢掉最旧的。 */
    @Override
    public void runFinished(FinishedRun run) {
        if (closed || !settings.enabled() || !worthNoting(run) || run.offRecord()) {
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
                catchUp(run.user());
                tidy(run.user());
            }
        }
    }

    /** 记一轮；返回写没写。测试直接调。 */
    boolean note(FinishedRun run) {
        try {
            if (!userWants.test(run.user())) {
                return false;
            }
            ZoneId zone = zones.apply(run.user());
            String day = LocalDate.ofInstant(run.end(), zone).toString();
            DailyNotes daily = notes.apply(run.user());
            tantalum.memory.ledger.Ledger ledger = daily.ledger();
            if (ledger.hasMarker(CAPTURED + run.run().value()) || daily.noted(day, run.session().value(), run.run().value())) {
                return false;
            }
            if (ledger.markers().stream().noneMatch(marker -> marker.startsWith(CATCHUP_FROM))) {
                ledger.mark(CATCHUP_FROM + run.start());
            }
            String turn = capped(turnText(run.segments()), settings.maxInputChars());
            if (turn.isBlank()) {
                ledger.mark(CAPTURED + run.run().value());
                return false;
            }
            String title = run.title();
            NoteFiler filer = filers.apply(run.user());
            String reply = ask(run, NotePrompt.user(title, day, turn, filer.open(), filer.known()));
            if (reply == null) {
                // 模型失败：不记标记，之后补抓
                return false;
            }
            String heading = TIME.format(run.start().atZone(zone)) + (run.turn() == null ? "" : " · #" + run.turn());
            NoteFiler.Filed filed = filer.file(new NoteFiler.Turn(run.session().value(), run.run().value(), day,
                    title, heading, run.segments(), tainted(run.segments())), NotePrompt.notes(reply));
            ledger.mark(CAPTURED + run.run().value());
            return filed.kept() + filed.pending() > 0;
        } catch (RuntimeException failure) {
            LOG.warn("memory note for run {} failed: {}", run.run().value(), failure.toString());
            return false;
        }
    }

    /** 给模型看的这一轮：你说的话（包括问答卡里的回答）和最后的回答；工具的输出不给（memory-final-plan 第 7 节）。 */
    static String turnText(List<RunSegment> segments) {
        StringBuilder text = new StringBuilder();
        String answer = null;
        for (RunSegment segment : segments) {
            if (segment.role() == RunSegment.Role.USER) {
                text.append("User: ").append(segment.text()).append("\n\n");
            } else if (segment.role() == RunSegment.Role.TOOL && "ask_user".equals(segment.tool()) && !segment.text().isBlank()) {
                if (segment.asked() != null && !segment.asked().isBlank()) {
                    text.append("Assistant asked (question and choices): ").append(segment.asked().replace('\n', ' ')).append('\n');
                }
                text.append("User (answering the assistant's question): ").append(segment.text()).append("\n\n");
            } else if (segment.role() == RunSegment.Role.ASSISTANT) {
                answer = segment.text();
            }
        }
        if (text.isEmpty()) {
            return "";
        }
        if (answer != null) {
            text.append("Assistant: ").append(answer).append('\n');
        }
        return text.toString().strip();
    }

    /** 这一轮读过外部内容（见 {@link Quotes#tainted}）。 */
    static boolean tainted(List<RunSegment> segments) {
        return Quotes.tainted(segments);
    }

    private volatile Function<Principal, tantalum.memory.LibrarianProposals.Result> proposals = user -> null;

    /** 每天整理以后再让模型提议（{@link tantalum.memory.LibrarianProposals}，提议都进“待确认”）；不设时不提议。 */
    public NoteTaker proposals(Function<Principal, tantalum.memory.LibrarianProposals.Result> proposer) {
        this.proposals = proposer;
        return this;
    }

    /** 每人每天整理一次（{@link tantalum.memory.Librarian}），在处理通知的后台线程上做；出错只记日志。 */
    void tidy(Principal user) {
        if (closed) {
            return;
        }
        try {
            tantalum.memory.Librarian.Result result = new tantalum.memory.Librarian(notes.apply(user).ledger(), zones.apply(user)).daily();
            if (result != null && result.merged() > 0) {
                LOG.info("memory librarian for {}: merged {} duplicate(s){}", user.value(), result.merged(),
                        result.rolledBack() ? ", rolled back because recall got worse" : "");
            }
            if (result != null) {
                tantalum.memory.LibrarianProposals.Result proposed = proposals.apply(user);
                if (proposed != null && proposed.added() + proposed.corrections() + proposed.closes() > 0) {
                    LOG.info("memory librarian for {} proposed {} addition(s), {} correction(s), {} close(s)", user.value(),
                            proposed.added(), proposed.corrections(), proposed.closes());
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("memory librarian for {} failed: {}", user.value(), failure.toString());
        }
    }

    /**
     * 补抓（memory-final-plan 第 9 节“可靠捕获”）：这个人第一次记笔记以后、最近 7 天里正常结束但没处理过的轮次，逐个记。
     * 同一个人 10 分钟查一次；在处理通知的后台线程上做。
     */
    void catchUp(Principal user) {
        long now = System.currentTimeMillis();
        Long last = caughtUp.get(user);
        if (last != null && now - last < CATCHUP_EVERY.toMillis() || closed) {
            return;
        }
        caughtUp.put(user, now);
        try {
            if (!userWants.test(user)) {
                return;
            }
            tantalum.memory.ledger.Ledger ledger = notes.apply(user).ledger();
            java.util.Optional<java.time.Instant> from = ledger.markers().stream().filter(marker -> marker.startsWith(CATCHUP_FROM))
                    .map(marker -> java.time.Instant.parse(marker.substring(CATCHUP_FROM.length()))).findFirst();
            if (from.isEmpty()) {
                return;
            }
            java.time.Instant since = from.get().isAfter(java.time.Instant.now().minus(CATCHUP_WINDOW)) ? from.get()
                    : java.time.Instant.now().minus(CATCHUP_WINDOW);
            int done = 0;
            for (tantalum.shell.plugin.PluginServices.FinishedRef ref : catchup.finished(user, since)) {
                if (closed || done >= CATCHUP_LIMIT) {
                    break;
                }
                // 补记让路（review 2026-11-17）：后台只有一个工人，一个人的补记最多 50 轮、每轮一次模型调用；
                // 有刚结束的一轮在等（可能是别人的）就先停下，下一次轮到这个人时接着补
                if (done > 0 && !queue.isEmpty()) {
                    caughtUp.remove(user);
                    break;
                }
                if (ref.manual() || ref.offRecord() || ref.end() == null || !"COMPLETED".equals(ref.endReason())
                        || ledger.hasMarker(CAPTURED + ref.run().value())) {
                    continue;
                }
                List<RunSegment> segments = catchup.view(user, ref.session(), ref.run());
                if (segments.isEmpty()) {
                    continue;
                }
                note(new FinishedRun(user, ref.session(), ref.run(), ref.turn(), ref.endReason(), false, ref.start(), ref.end(), "", "",
                        segments, false));
                done++;
            }
            if (done > 0) {
                LOG.info("memory notes caught up {} run(s) for {}", done, user.value());
            }
        } catch (RuntimeException failure) {
            LOG.warn("memory note catch-up for {} failed: {}", user.value(), failure.toString());
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
            Optional<String> reply = writer.write(run.user(), NotePrompt.system(), user, settings.maxOutputTokens());
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
