package tantalum.shell.loop;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.journal.Sequence;
import tantalum.shell.journal.Line;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.PrincipalState;

/**
 * 一个用户的写入通道。有事件时一个虚拟线程，没事件就退出。取走队列里的全部工作，事件按到达顺序取号后放行给 cleanroom，
 * 不写文件；cleanroom 处理完的一步（{@link Released}）连同它的事件写成一行（one-line-journal J1、J2），
 * 每个会话并行写、fsync 一次，全部落盘后才放行命令、推送和索引。所以同一用户内严格按序，不同用户互不等待；
 * 没处理完的事件不在文件里，崩溃时它的命令也没执行过。一个用户的文件只有这个通道在写。
 */
public final class UserLane {

    private static final Logger LOG = LoggerFactory.getLogger(UserLane.class);

    /** 把一个会话处理完的几步按顺序写到它的文件，返回时已落盘。 */
    @FunctionalInterface
    public interface Writer {
        void append(Principal principal, SessionId session, List<Line> lines) throws IOException;
    }

    /** cleanroom 处理完的一步，见 {@link Release}。 */
    record Released(EventEnvelope envelope, StepRecord record, Runnable afterDurable, Runnable done) {}

    /** 读一个冷会话里序号小于 {@code beforeSeq} 的步骤。没有这样的步骤（全新会话）时为空。 */
    public interface Loader {
        Optional<CleanroomRing.Loaded> load(Principal principal, SessionId session, long beforeSeq) throws Exception;

        /** 这个用户序号小于 {@code beforeSeq} 的累计用量。为 null 时不并入。 */
        PrincipalState usage(Principal principal, long beforeSeq) throws Exception;
    }

    /** 通道的下游。 */
    interface Exit {
        boolean halted();

        boolean publish(EventEnvelope envelope, PrincipalState usage, CleanroomRing.Load load);

        /** 写入失败，进程停止处理事件。 */
        void failed(Throwable failure);

        /** 这些事件不会再被放行。 */
        void discarded(int count);
    }

    private final Principal principal;
    private final Sequence sequence;
    private final Writer writer;
    private final Exit exit;
    private final Loader loader;
    private final Residency residency;
    /** {@link EventEnvelope} 或 {@link Released}，按到达顺序。 */
    private final Queue<Object> queue = new ArrayDeque<>();
    private boolean running;
    /** 只有通道线程访问。用户的用量在本进程只载入一次，跟着他的第一个事件。 */
    private boolean usageLoaded;

    UserLane(Principal principal, Sequence sequence, Writer writer, Exit exit, Loader loader, Residency residency) {
        this.loader = loader;
        this.residency = residency;
        this.principal = principal;
        this.sequence = sequence;
        this.writer = writer;
        this.exit = exit;
    }

    void offer(EventEnvelope envelope) {
        enqueue(envelope);
    }

    void offer(Released released) {
        enqueue(released);
    }

    /** 放入和判断是否要启动线程在同一把锁下，线程退出时也一样，所以不会有事件留在没人取的队列里。 */
    private synchronized void enqueue(Object item) {
        queue.add(item);
        if (!running) {
            running = true;
            Thread.ofVirtual().name("lane-" + principal.value()).start(this::drain);
        }
    }

    private void drain() {
        while (true) {
            List<Object> batch = take();
            if (batch.isEmpty()) {
                return;
            }
            process(batch);
        }
    }

    private synchronized List<Object> take() {
        List<Object> batch = new ArrayList<>(queue);
        queue.clear();
        running = !batch.isEmpty();
        return batch;
    }

    private void process(List<Object> items) {
        List<EventEnvelope> batch = new ArrayList<>();
        List<Released> released = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Released step) {
                released.add(step);
            } else {
                batch.add((EventEnvelope) item);
            }
        }
        if (exit.halted()) {
            exit.discarded(batch.size());
            released.forEach(step -> step.done().run());
            return;
        }
        Map<SessionId, Residency.State> states = new HashMap<>();
        for (EventEnvelope envelope : batch) {
            Residency.State state = residency.pin(envelope.session());
            states.putIfAbsent(envelope.session(), state);
        }
        try {
            write(released);
        } catch (Exception | Error failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            exit.failed(failure);
            batch.forEach(envelope -> residency.unpin(envelope.session()));
            exit.discarded(batch.size());
            released.forEach(step -> step.done().run());
            return;
        }
        for (Released step : released) {
            try {
                step.afterDurable().run();
            } catch (RuntimeException failure) {
                LOG.error("releasing the step of seq {} failed", step.envelope().seq(), failure);
            } finally {
                step.done().run();
            }
        }
        List<EventEnvelope> numbered = new ArrayList<>(batch.size());
        try {
            for (EventEnvelope envelope : batch) {
                numbered.add(envelope.withSeq(sequence.next(principal)));
            }
        } catch (RuntimeException failure) {
            exit.failed(failure);
            batch.forEach(envelope -> residency.unpin(envelope.session()));
            exit.discarded(batch.size());
            return;
        }
        if (numbered.isEmpty()) {
            return;
        }
        Map<SessionId, CleanroomRing.Load> loads = load(numbered, states);
        PrincipalState usage = usage(numbered.getFirst().seq());
        for (int i = 0; i < numbered.size(); i++) {
            EventEnvelope envelope = numbered.get(i);
            CleanroomRing.Load load = loads.get(envelope.session());
            if (load instanceof CleanroomRing.Loaded) {
                loads.remove(envelope.session());
            }
            if (!exit.publish(envelope, usage, load)) {
                exit.failed(new IllegalStateException("cleanroom ring refused an event although a permit was held"));
                numbered.subList(i, numbered.size()).forEach(left -> residency.unpin(left.session()));
                exit.discarded(numbered.size() - i);
                return;
            }
            usage = null;
        }
    }

    /** 文件里只有处理完的步骤，序号都小于新事件。切片跟着这个会话的第一个事件走；失败的会话每个事件都带着失败。 */
    private Map<SessionId, CleanroomRing.Load> load(List<EventEnvelope> numbered, Map<SessionId, Residency.State> states) {
        Map<SessionId, CleanroomRing.Load> loads = new HashMap<>();
        Set<SessionId> visited = new HashSet<>();
        for (EventEnvelope envelope : numbered) {
            SessionId session = envelope.session();
            if (!visited.add(session)) {
                continue;
            }
            switch (states.get(session)) {
                case RESIDENT -> { }
                case FAILED -> loads.put(session, new CleanroomRing.Failed(residency.failure(session)));
                case COLD -> loadOne(session, envelope.seq()).ifPresent(load -> loads.put(session, load));
            }
        }
        return loads;
    }

    /** 失败时下一批再试；在那之前钩子看到的用量偏少，所以记错误日志。 */
    private PrincipalState usage(long beforeSeq) {
        if (usageLoaded) {
            return null;
        }
        try {
            PrincipalState usage = loader.usage(principal, beforeSeq);
            usageLoaded = true;
            return usage;
        } catch (Exception | Error failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("cannot load usage of {}; quota hooks see less than the real usage until it loads", principal.value(), failure);
            return null;
        }
    }

    private Optional<CleanroomRing.Load> loadOne(SessionId session, long beforeSeq) {
        try {
            Optional<CleanroomRing.Loaded> loaded = loader.load(principal, session, beforeSeq);
            residency.resident(session);
            return loaded.map(CleanroomRing.Load.class::cast);
        } catch (Exception | Error failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("cannot load session {}; refusing it until restart", session.value(), failure);
            String reason = failure.toString();
            residency.fail(session, reason);
            return Optional.of(new CleanroomRing.Failed(reason));
        }
    }

    private void write(List<Released> released) throws IOException, InterruptedException, ExecutionException {
        Map<SessionId, List<Line>> bySession = new LinkedHashMap<>();
        for (Released step : released) {
            bySession.computeIfAbsent(step.envelope().session(), ignored -> new ArrayList<>()).add(new Line(step.envelope(), step.record()));
        }
        if (bySession.isEmpty()) {
            return;
        }
        if (bySession.size() == 1) {
            var only = bySession.entrySet().iterator().next();
            writer.append(principal, only.getKey(), only.getValue());
            return;
        }
        // close 等全部写完，所以失败时也不会有写入还在进行。
        try (ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            bySession.forEach((session, lines) -> running.add(writers.submit(() -> {
                writer.append(principal, session, lines);
                return null;
            })));
            for (Future<?> write : running) {
                write.get();
            }
        }
    }
}
