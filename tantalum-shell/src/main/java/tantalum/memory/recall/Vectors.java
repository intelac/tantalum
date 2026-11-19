package tantalum.memory.recall;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.memory.ledger.MemoryItem;

/**
 * 一个人的记忆向量（memory-final-plan 第 9 节第 3 步）：记忆账上每条生效的说法和当天卡一个向量，按意思找的时候用。
 * 向量是从账算出来的，随时可以整个重算，所以不进账，单独一个文件 {@code vectors.jsonl}（每行：条目号、文字的指纹、向量），
 * 和账放在一起，用户自己的工具碰不到。文字变了（指纹不同）就重算；条目不在了就丢掉。
 * <p>向量由插件参数里配的向量服务给（{@link Embedder}），给不出来时这里什么都不做，找回照只有关键词的办法走。
 */
public final class Vectors {

    private static final Logger LOG = LoggerFactory.getLogger(Vectors.class);
    public static final String FILE = "vectors.jsonl";

    /** 把文字变成向量；{@code query} 为真是拿来找的话。给不出来为空。 */
    @FunctionalInterface
    public interface Embedder {
        Optional<List<float[]>> embed(List<String> texts, boolean query);

        /** 是哪个模型、多少维算的：换了以后旧向量和新向量不能比，指纹里带上它，换了就整个重算。 */
        default String stamp() {
            return "";
        }
    }

    /** 一条按意思找到的：条目号和余弦相似度。 */
    public record Scored(String id, double cosine) {}

    private record Entry(String hash, float[] vector) {}

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean loaded;

    public Vectors(Path dir) {
        this.file = dir.resolve(FILE);
    }

    /** 有向量的条目数。 */
    public synchronized int size() {
        load();
        return entries.size();
    }

    /**
     * 让向量跟上账：{@code live} 里还没有向量或文字变了的，分批算出来（这一次最多 {@code max} 条，剩下的下次）；不在 {@code live} 里的丢掉。
     * 返回这次新算了几条。服务给不出来时停下，已有的照用。
     */
    public synchronized int sync(List<MemoryItem> live, Embedder embedder, int max) {
        load();
        Set<String> ids = new HashSet<>();
        List<MemoryItem> missing = new ArrayList<>();
        for (MemoryItem item : live) {
            ids.add(item.id());
            Entry known = entries.get(item.id());
            if ((known == null || !known.hash().equals(hash(embedder.stamp() + item.text()))) && missing.size() < max && !item.text().isBlank()) {
                missing.add(item);
            }
        }
        boolean dropped = entries.keySet().removeIf(id -> !ids.contains(id));
        int done = 0;
        for (int from = 0; from < missing.size(); from += BATCH) {
            List<MemoryItem> batch = missing.subList(from, Math.min(missing.size(), from + BATCH));
            Optional<List<float[]>> vectors = embedder.embed(batch.stream().map(MemoryItem::text).toList(), false);
            if (vectors.isEmpty() || vectors.get().size() != batch.size()) {
                break;
            }
            for (int i = 0; i < batch.size(); i++) {
                entries.put(batch.get(i).id(), new Entry(hash(embedder.stamp() + batch.get(i).text()), vectors.get().get(i)));
                done++;
            }
        }
        if (done > 0 || dropped) {
            save();
        }
        return done;
    }

    static final int BATCH = 16;

    /** 和 {@code query} 最像的 {@code limit} 条，相似度至少 {@code floor}，像的在前。维数对不上的（换过向量模型）不算。 */
    public synchronized List<Scored> nearest(float[] query, int limit, double floor) {
        load();
        List<Scored> scored = new ArrayList<>();
        for (Map.Entry<String, Entry> each : entries.entrySet()) {
            float[] vector = each.getValue().vector();
            if (vector.length != query.length) {
                continue;
            }
            double dot = 0;
            for (int i = 0; i < vector.length; i++) {
                dot += vector[i] * query[i];
            }
            if (dot >= floor) {
                scored.add(new Scored(each.getKey(), dot));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::cosine).reversed().thenComparing(Scored::id));
        return scored.size() > limit ? List.copyOf(scored.subList(0, limit)) : List.copyOf(scored);
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) {
                    continue;
                }
                ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(parts[2]));
                float[] vector = new float[bytes.remaining() / Float.BYTES];
                bytes.asFloatBuffer().get(vector);
                entries.put(parts[0], new Entry(parts[1], vector));
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            // 坏了就当没有：向量可以从账重算
            LOG.warn("memory vectors at {} unreadable, rebuilding: {}", file, unreadable.toString());
            entries.clear();
        }
    }

    /** 整个文件重写（先写旁边的临时文件再换名）：条数不多，写一次几兆以内。 */
    private void save() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Entry> each : entries.entrySet()) {
            ByteBuffer bytes = ByteBuffer.allocate(each.getValue().vector().length * Float.BYTES);
            bytes.asFloatBuffer().put(each.getValue().vector());
            out.append(each.getKey()).append('\t').append(each.getValue().hash()).append('\t')
                    .append(Base64.getEncoder().encodeToString(bytes.array())).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE + ".tmp");
            Files.writeString(temp, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            LOG.warn("cannot save memory vectors at {}: {}", file, failure.toString());
        }
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
