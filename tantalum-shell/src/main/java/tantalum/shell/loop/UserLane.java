package tantalum.shell.loop;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 一个用户的写入通道。有事件时一个虚拟线程，没事件就退出。取走队列里的全部事件，按到达顺序取号，
 * 每个会话并行写文件，全部落盘后按序号放行。所以同一用户内严格按序，不同用户互不等待。
 */
public final class UserLane {

    /** 把一个会话的事件写到它的文件，返回时已落盘。 */
    @FunctionalInterface
    public interface Writer {
        void append(Principal principal, SessionId session, List<EventEnvelope> events) throws IOException;
    }

    /** 通道的下游。 */
    interface Exit {
        boolean halted();

        boolean publish(EventEnvelope envelope);

        /** 写入失败，进程停止处理事件。 */
        void failed(Throwable failure);

        /** 这些事件不会再被放行。 */
        void discarded(int count);
    }

    private final Principal principal;
    private final Sequence sequence;
    private final Writer writer;
    private final Exit exit;
    private final Queue<EventEnvelope> queue = new ArrayDeque<>();
    private boolean running;

    UserLane(Principal principal, Sequence sequence, Writer writer, Exit exit) {
        this.principal = principal;
        this.sequence = sequence;
        this.writer = writer;
        this.exit = exit;
    }

    /** 放入和判断是否要启动线程在同一把锁下，线程退出时也一样，所以不会有事件留在没人取的队列里。 */
    synchronized void offer(EventEnvelope envelope) {
        queue.add(envelope);
        if (!running) {
            running = true;
            Thread.ofVirtual().name("lane-" + principal.value()).start(this::drain);
        }
    }

    private void drain() {
        while (true) {
            List<EventEnvelope> batch = take();
            if (batch.isEmpty()) {
                return;
            }
            process(batch);
        }
    }

    private synchronized List<EventEnvelope> take() {
        List<EventEnvelope> batch = new ArrayList<>(queue);
        queue.clear();
        running = !batch.isEmpty();
        return batch;
    }

    private void process(List<EventEnvelope> batch) {
        if (exit.halted()) {
            exit.discarded(batch.size());
            return;
        }
        List<EventEnvelope> numbered = new ArrayList<>(batch.size());
        try {
            for (EventEnvelope envelope : batch) {
                numbered.add(envelope.withSeq(sequence.next(principal)));
            }
            write(numbered);
        } catch (Exception | Error failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            exit.failed(failure);
            exit.discarded(batch.size());
            return;
        }
        for (int i = 0; i < numbered.size(); i++) {
            if (!exit.publish(numbered.get(i))) {
                exit.failed(new IllegalStateException("cleanroom ring refused an event although a permit was held"));
                exit.discarded(numbered.size() - i);
                return;
            }
        }
    }

    private void write(List<EventEnvelope> numbered) throws IOException, InterruptedException, ExecutionException {
        Map<SessionId, List<EventEnvelope>> bySession = new LinkedHashMap<>();
        for (EventEnvelope envelope : numbered) {
            bySession.computeIfAbsent(envelope.session(), ignored -> new ArrayList<>()).add(envelope);
        }
        if (bySession.size() == 1) {
            var only = bySession.entrySet().iterator().next();
            writer.append(principal, only.getKey(), only.getValue());
            return;
        }
        // close 等全部写完，所以失败时也不会有写入还在进行。
        try (ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            bySession.forEach((session, events) -> running.add(writers.submit(() -> {
                writer.append(principal, session, events);
                return null;
            })));
            for (Future<?> write : running) {
                write.get();
            }
        }
    }
}
