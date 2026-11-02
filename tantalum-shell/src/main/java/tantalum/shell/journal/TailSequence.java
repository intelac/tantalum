package tantalum.shell.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import tantalum.shell.journal.Sequence;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.id.Principal;

/** 第一次见到用户时读他所有会话文件的尾部，从最大值往后取号。读文件只拖慢这个用户。同一用户由它的通道串行调用。 */
public final class TailSequence implements Sequence {

    private final SessionPaths paths;
    private final int step;
    private final Map<Principal, AtomicLong> next = new ConcurrentHashMap<>();

    /** {@code step} 大于 1 时跳号。以后多实例接管会产生跳号，回归测试用它证明没有代码依赖连续。 */
    public TailSequence(SessionPaths paths, int step) {
        this.paths = paths;
        this.step = step;
    }

    @Override
    public long next(Principal principal) {
        AtomicLong counter = next.get(principal);
        if (counter == null) {
            counter = new AtomicLong(firstFree(principal));
            next.put(principal, counter);
        }
        long seq = counter.get();
        if (seq > MAX) {
            throw new IllegalStateException("sequence of " + principal.value() + " exceeded " + MAX);
        }
        counter.addAndGet(step);
        return seq;
    }

    private long firstFree(Principal principal) {
        try {
            long last = -1;
            for (Path file : paths.sessionFiles(paths.userDir(principal))) {
                last = Math.max(last, SessionFileReader.lastSeq(file));
            }
            return last + 1;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
