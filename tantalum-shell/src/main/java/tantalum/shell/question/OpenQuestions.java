package tantalum.shell.question;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tantalum.shell.plugin.Question;
import tantalum.shell.plugin.Questions;
import tantalum.shell.plugin.ToolContext;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 控制台上等用户回答的问题（{@link Questions} 的实现）。按（用户、会话、问题 id）记，只在内存里：问题本身不进 journal，
 * 回答由提问的工作写进它的结果，journal 里记的是结果。重启后问题没了，等着的工作被关服务打断，运行按“因重启中断”收尾。
 * 问题出现、撤下时通知 {@link Listener}（推一条 SSE），页面再来取。
 */
public final class OpenQuestions implements Questions {

    /** 没人在等、最后一次问过之后这么久没人再问，就撤下：插件的下一次询问通常紧跟着上一次。 */
    static final Duration GRACE = Duration.ofSeconds(5);

    /** 问题有了变化。必须马上返回。 */
    @FunctionalInterface
    public interface Listener {
        void changed(Principal user, SessionId session);
    }

    /** 回答的结果：收下了；问题已经不在（答过、撤下、不是这个用户的）；不是它的选项。 */
    public enum Answer { ACCEPTED, GONE, NOT_A_CHOICE }

    private record Key(Principal user, SessionId session, String id) {}

    /** 一个等回答的问题。{@code waiting} 和 {@code lastAsked} 只在持有外面的锁时读写。 */
    private static final class Pending {
        final Question question;
        final CompletableFuture<String> answer = new CompletableFuture<>();
        int waiting;
        Instant lastAsked;

        Pending(Question question) {
            this.question = question;
        }
    }

    private final Clock clock;
    private final Listener listener;
    private final Map<Key, Pending> pending = new LinkedHashMap<>();

    public OpenQuestions(Clock clock, Listener listener) {
        this.clock = clock;
        this.listener = listener;
    }

    @Override
    public Optional<String> ask(ToolContext context, Question question, Duration wait) throws InterruptedException {
        Key key = new Key(context.principal(), context.session(), question.id());
        Pending asked;
        boolean created;
        synchronized (this) {
            sweep();
            asked = pending.get(key);
            created = asked == null;
            if (created) {
                asked = new Pending(question);
                pending.put(key, asked);
            }
            asked.waiting++;
            asked.lastAsked = clock.instant();
        }
        if (created) {
            listener.changed(key.user(), key.session());
        }
        try {
            String choice = asked.answer.get(Math.max(1, wait.toMillis()), TimeUnit.MILLISECONDS);
            withdraw(key, asked);
            return Optional.of(choice);
        } catch (TimeoutException unanswered) {
            return Optional.empty();
        } catch (InterruptedException stopped) {
            withdraw(key, asked);
            throw stopped;
        } catch (ExecutionException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            synchronized (this) {
                asked.waiting--;
                asked.lastAsked = clock.instant();
            }
        }
    }

    /** 用户的回答。只能回答自己的、还在的问题。 */
    public Answer answer(Principal user, SessionId session, String id, String choice) {
        Pending found;
        synchronized (this) {
            sweep();
            found = pending.get(new Key(user, session, id));
        }
        if (found == null) {
            return Answer.GONE;
        }
        if (!found.question.offers(choice)) {
            return Answer.NOT_A_CHOICE;
        }
        return found.answer.complete(choice) ? Answer.ACCEPTED : Answer.GONE;
    }

    /** 这个用户这个会话里还没回答的问题，按提问的先后。 */
    public synchronized List<Question> open(Principal user, SessionId session) {
        sweep();
        List<Question> open = new ArrayList<>();
        pending.forEach((key, question) -> {
            if (key.user().equals(user) && key.session().equals(session) && !question.answer.isDone()) {
                open.add(question.question);
            }
        });
        return open;
    }

    private void withdraw(Key key, Pending asked) {
        boolean removed;
        synchronized (this) {
            removed = pending.remove(key, asked);
        }
        if (removed) {
            listener.changed(key.user(), key.session());
        }
    }

    /** 撤下没人在等、也很久没人再问的问题（插件不再问了：放弃了、运行在两次询问之间被取消）。 */
    private void sweep() {
        Instant now = clock.instant();
        Iterator<Map.Entry<Key, Pending>> entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, Pending> entry = entries.next();
            Pending question = entry.getValue();
            if (question.waiting == 0 && question.lastAsked.plus(GRACE).isBefore(now)) {
                entries.remove();
            }
        }
    }
}
