package tantalum.memory.recall;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.memory.MemoryStores;
import tantalum.value.id.Principal;

/**
 * 按意思找（memory-final-plan 第 9 节第 3 步）：把问题变成向量，在这个人的记忆向量里找最像的几条，交给 {@link Recall} 和关键词
 * 一路合起来。这个人关了“按意思找回”、没配向量服务、服务慢了（超过 {@link #QUERY_MILLIS}）或失败时返回空，找回照只有关键词的办法走，不耽误回答。
 * <p>记忆的向量在后台跟上账（每次找的时候顺手排一次，一次最多算 {@link #SYNC_AT_ONCE} 条），所以新记的东西要等一两轮才按意思找得到；
 * 这期间关键词一路照常。一个人第一次用、已有的记忆还一条向量都没有时，先等一小会儿把它们算出来。
 */
public final class Semantic implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Semantic.class);
    /**
     * 相似度门槛：低于它的不算找到。0.55 是照本机 qwen3-embedding（问句带说明、取前 1024 维）试出来的：相关的在 0.56–0.78，
     * 不相关的不超过 0.5。换了向量模型要重新试。
     */
    public static final double FLOOR = 0.55;
    static final int NEAR = 12;
    static final long QUERY_MILLIS = 1500;
    static final int SYNC_AT_ONCE = 64;

    private final MemoryStores stores;
    /** 这个人的向量服务（照他此刻的插件参数）：关着时每次都给空。 */
    private final java.util.function.Function<Principal, Vectors.Embedder> embedders;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memory-vectors");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Principal> syncing = ConcurrentHashMap.newKeySet();

    /** 一个人第一次按意思找、还一条向量都没有时，最多等这么久把已有的记忆算出来（之后都在后台跟）。 */
    private final java.time.Duration firstWait;

    public Semantic(MemoryStores stores, java.util.function.Function<Principal, Vectors.Embedder> embedders, java.time.Duration firstWait) {
        this.stores = stores;
        this.embedders = embedders;
        this.firstWait = firstWait;
    }

    /** 和这句话意思最像的记忆，像的在前；给不出来为空。 */
    public List<Vectors.Scored> near(Principal user, String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Vectors vectors = stores.vectors(user);
        Vectors.Embedder embedder = embedders.apply(user);
        if (vectors.size() == 0) {
            first(user, vectors, embedder);
        } else {
            catchUp(user, vectors, embedder);
        }
        if (vectors.size() == 0) {
            return List.of();
        }
        try {
            Optional<List<float[]>> asked = CompletableFuture.supplyAsync(() -> embedder.embed(List.of(question), true))
                    .get(QUERY_MILLIS, TimeUnit.MILLISECONDS);
            return asked.isEmpty() || asked.get().isEmpty() ? List.of() : vectors.nearest(asked.get().getFirst(), NEAR, FLOOR);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception slowOrFailed) {
            return List.of();
        }
    }

    /**
     * 第一次：这个人已有的记忆还没有向量。在后台一批一批算，这里最多等 {@link #firstWait}；没算完的后台接着算，这一次先用算好的。
     */
    private void first(Principal user, Vectors vectors, Vectors.Embedder embedder) {
        if (firstWait.isZero() || firstWait.isNegative() || !syncing.add(user)) {
            catchUp(user, vectors, embedder);
            return;
        }
        long deadline = System.nanoTime() + firstWait.toNanos();
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    Recall recall = new Recall(stores.ledger(user));
                    while (vectors.sync(recall.embeddable(), embedder, SYNC_AT_ONCE) > 0 && System.nanoTime() < deadline) {
                        // 一批一批来，直到都有了或者时间到
                    }
                } catch (RuntimeException failure) {
                    LOG.warn("memory vectors for {} failed: {}", user.value(), failure.toString());
                } finally {
                    syncing.remove(user);
                }
            }, worker).get(firstWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception slowOrClosed) {
            // 时间到了：后台接着算，这一次用已经算好的
        }
    }

    /** 后台让这个人的向量跟上账；同一个人同时只排一次。 */
    void catchUp(Principal user, Vectors vectors, Vectors.Embedder embedder) {
        if (!syncing.add(user)) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    int done = vectors.sync(new Recall(stores.ledger(user)).embeddable(), embedder, SYNC_AT_ONCE);
                    if (done > 0) {
                        LOG.info("memory vectors: {} new for {}", done, user.value());
                    }
                } catch (RuntimeException failure) {
                    LOG.warn("memory vectors for {} failed: {}", user.value(), failure.toString());
                } finally {
                    syncing.remove(user);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            syncing.remove(user);
        }
    }

    /** 测试用：等后台排着的都做完。 */
    void drain() throws InterruptedException {
        CompletableFuture<Void> done = CompletableFuture.runAsync(() -> { }, worker);
        try {
            done.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
