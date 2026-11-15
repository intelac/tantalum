package tantalum.shell.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.plugin.PromptAttacher.Submission;
import tantalum.shell.plugin.PromptAttacher;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.AttachedBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;

/**
 * “提交时附加内容”插件点的平台一侧（memory-plugin 需求 1.2）：同时问这个用户开着的每个附加者，大家共用一个时限，
 * 超时的、出错的、空的都不附。结果按附加者的顺序。
 */
public final class PromptAttachers {

    public static final Duration BUDGET = Duration.ofMillis(200);
    static final int MAX_CHARS = 8_000;
    private static final Logger LOG = LoggerFactory.getLogger(PromptAttachers.class);

    private final List<PromptAttacher> attachers;
    private final BiPredicate<String, Principal> enabled;
    private final Duration budget;

    PromptAttachers(List<PromptAttacher> attachers, BiPredicate<String, Principal> enabled, Duration budget) {
        this.attachers = List.copyOf(attachers);
        this.enabled = enabled;
        this.budget = budget;
    }

    /** {@code history} 是会话里已有的消息，用来给每个插件找它以前附过的内容。 */
    public List<AttachedBlock> collect(Principal user, SessionId session, String text, List<Message> history) {
        List<PromptAttacher> mine = attachers.stream().filter(attacher -> enabled.test(attacher.plugin(), user)).toList();
        if (mine.isEmpty()) {
            return List.of();
        }
        List<Future<Optional<String>>> pending = new ArrayList<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (PromptAttacher attacher : mine) {
                Submission submission = new Submission(user, session, text, before(history, attacher.plugin()));
                pending.add(threads.submit(() -> attacher.attach(submission)));
            }
            long deadline = System.nanoTime() + budget.toNanos();
            List<AttachedBlock> attached = new ArrayList<>();
            for (int i = 0; i < mine.size(); i++) {
                String plugin = mine.get(i).plugin();
                Future<Optional<String>> future = pending.get(i);
                try {
                    Optional<String> result = future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    result.filter(value -> !value.isBlank())
                            .map(value -> value.length() > MAX_CHARS ? value.substring(0, MAX_CHARS) : value)
                            .ifPresent(value -> attached.add(new AttachedBlock(plugin, value)));
                } catch (TimeoutException slow) {
                    future.cancel(true);
                    LOG.info("plugin {} took longer than {} ms to attach; skipped this turn", plugin, budget.toMillis());
                } catch (ExecutionException failure) {
                    LOG.warn("plugin {} failed to attach: {}", plugin, failure.getCause() == null ? failure : failure.getCause().toString());
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    pending.forEach(each -> each.cancel(true));
                    return attached;
                }
            }
            threads.shutdownNow();
            return attached;
        }
    }

    private static List<String> before(List<Message> history, String plugin) {
        List<String> texts = new ArrayList<>();
        for (Message message : history) {
            for (ContentBlock block : message.content()) {
                if (block instanceof AttachedBlock attached && attached.plugin().equals(plugin)) {
                    texts.add(attached.text());
                }
            }
        }
        return texts;
    }
}
