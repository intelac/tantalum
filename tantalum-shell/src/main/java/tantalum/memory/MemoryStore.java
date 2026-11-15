package tantalum.memory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;

/**
 * 一个用户的长期记忆条目（memory_read / memory_write / memory_delete 和记忆页用）。底下是记忆账：一条记忆 = 一个页面
 * （{@link MemoryItem.Kind#PAGE}，名字、类型、描述）加它上面有效的说法（{@link MemoryItem.Kind#CLAIM}）。覆盖写入时旧说法标成
 * 被取代，不删；删除是墓碑。索引按条目生成，进 system 前的样子和旧格式逐字相同。线程安全（写在本实例的锁内）。
 */
public final class MemoryStore {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryStore.class);
    static final String TYPE = "type";
    static final String DESCRIPTION = "description";

    /** 写入结果：成功时说明是新建还是覆盖，失败时给出能直接转给模型或用户的原因。 */
    public sealed interface WriteResult {
        record Created(MemoryEntry entry) implements WriteResult {}
        record Updated(MemoryEntry entry) implements WriteResult {}
        /** {@code reason} 是英文，原样给模型（memory_write 的结果）；{@code problem} 和 {@code params} 给控制台按语言说。 */
        record Rejected(Problem problem, String reason, Map<String, Object> params) implements WriteResult {}
    }

    /** 拒绝的种类。 */
    public enum Problem { INVALID_NAME, RESERVED_NAME, INVALID, TOO_LONG, FULL, INDEX_FULL, CREDENTIAL }

    private final Ledger ledger;
    private final MemoryLimits limits;

    public MemoryStore(Ledger ledger, MemoryLimits limits) {
        this.ledger = ledger;
        this.limits = limits;
    }

    /** 测试和单独使用：在 {@code dir} 打开一本账。 */
    public MemoryStore(Path dir, MemoryLimits limits, Clock clock) {
        this(Ledger.open(dir, clock), limits);
    }

    /** 账所在的目录。 */
    public Path dir() {
        return ledger.file().getParent();
    }

    public Ledger ledger() {
        return ledger;
    }

    public MemoryLimits limits() {
        return limits;
    }

    /** 索引内容，进 system 前的样子：超限时截断并写明。没有记忆时为空串。 */
    public String indexText() {
        StringBuilder text = new StringBuilder();
        int used = 0;
        boolean cut = false;
        for (String line : MemoryFormat.formatIndex(list()).lines().toList()) {
            int bytes = MemoryFormat.utf8Length(line) + 1;
            if (used + bytes > limits.maxIndexBytes()) {
                cut = true;
                break;
            }
            text.append(line).append('\n');
            used += bytes;
        }
        if (cut) {
            text.append("(The memory index was truncated at ").append(limits.maxIndexBytes()).append(" bytes; some entries are not listed.)\n");
        }
        return text.toString();
    }

    /** 全部条目，按更新时间倒序。 */
    public List<MemoryEntry> list() {
        List<MemoryEntry> entries = new ArrayList<>();
        for (MemoryItem page : pages()) {
            entry(page).ifPresent(entries::add);
        }
        entries.sort(Comparator.comparing(MemoryEntry::updatedAt).reversed().thenComparing(MemoryEntry::name));
        return entries;
    }

    public Optional<MemoryEntry> read(String name) {
        if (!MemoryFormat.isValidName(name)) {
            return Optional.empty();
        }
        return page(name).flatMap(this::entry);
    }

    /** 新建或同名覆盖（模型写的）。 */
    public WriteResult write(String name, String type, String description, String content) {
        return write(name, type, description, content, MemoryItem.Actor.AGENT, MemoryItem.Source.ASSISTANT_INFERRED);
    }

    /** 新建或同名覆盖。校验、凭据检查和上限都在这里，调用方只需转达 {@code Rejected.reason()}。 */
    public WriteResult write(String name, String type, String description, String content, MemoryItem.Actor actor,
                             MemoryItem.Source source) {
        Optional<WriteResult.Rejected> invalid = MemoryFormat.validate(name, type, description, content, limits.maxEntryBytes());
        if (invalid.isPresent()) {
            return invalid.get();
        }
        Optional<String> credential = CredentialGuard.detect(description, content);
        if (credential.isPresent()) {
            LOG.info("memory write rejected: looks like {}", credential.get());
            return new WriteResult.Rejected(Problem.CREDENTIAL, CredentialGuard.REASON, Map.of());
        }
        MemoryType memoryType = MemoryType.parse(type).orElseThrow();
        synchronized (this) {
            List<MemoryEntry> existing = list();
            boolean replaces = existing.stream().anyMatch(other -> other.name().equals(name));
            if (!replaces && existing.size() >= limits.maxEntries()) {
                return new WriteResult.Rejected(Problem.FULL, "memory is full (" + existing.size() + "/" + limits.maxEntries()
                        + " entries); delete one with memory_delete or merge into an existing entry with memory_write",
                        Map.of("max", limits.maxEntries()));
            }
            MemoryEntry preview = new MemoryEntry(name, memoryType, description.trim(), content, ledger.clock().instant());
            List<MemoryEntry> after = new ArrayList<>(existing.stream().filter(other -> !other.name().equals(name)).toList());
            after.add(preview);
            int indexBytes = MemoryFormat.utf8Length(MemoryFormat.formatIndex(after));
            if (indexBytes > limits.maxIndexBytes()) {
                return new WriteResult.Rejected(Problem.INDEX_FULL, "memory index would be " + indexBytes + " bytes; the limit is "
                        + limits.maxIndexBytes() + ". Shorten descriptions or delete entries first", Map.of("limit", limits.maxIndexBytes()));
            }
            Map<String, String> attrs = Map.of(TYPE, memoryType.pageType(), DESCRIPTION, description.trim());
            Optional<MemoryItem> page = page(name);
            if (page.isEmpty()) {
                ledger.put(MemoryItem.create(ledger.newId(), MemoryItem.Kind.PAGE, source, actor, "", ledger.clock().instant())
                        .withPage(name).withAttrs(attrs), 0);
            } else if (!page.get().attrs().equals(attrs)) {
                ledger.put(page.get().withAttrs(attrs).withActor(actor), page.get().version());
            }
            MemoryItem claim = MemoryItem.create(ledger.newId(), MemoryItem.Kind.CLAIM, source, actor, content, ledger.clock().instant())
                    .withPage(name);
            ledger.put(claim, 0);
            for (MemoryItem old : claims(name)) {
                if (!old.id().equals(claim.id())) {
                    ledger.put(old.withSupersededBy(claim.id()), old.version());
                }
            }
            MemoryEntry entry = read(name).orElseThrow();
            return replaces ? new WriteResult.Updated(entry) : new WriteResult.Created(entry);
        }
    }

    /** 删除条目（页面和说法都留墓碑）；不存在返回 false。 */
    public boolean delete(String name) {
        return delete(name, MemoryItem.Actor.AGENT);
    }

    public boolean delete(String name, MemoryItem.Actor actor) {
        if (!MemoryFormat.isValidName(name)) {
            return false;
        }
        synchronized (this) {
            Optional<MemoryItem> page = page(name);
            if (page.isEmpty()) {
                return false;
            }
            for (MemoryItem claim : ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && name.equals(item.page()))) {
                ledger.tombstone(claim.id(), actor, "memory deleted");
            }
            ledger.tombstone(page.get().id(), actor, "memory deleted");
            return true;
        }
    }

    private List<MemoryItem> pages() {
        return ledger.items(item -> item.kind() == MemoryItem.Kind.PAGE && item.state() == MemoryItem.State.CONFIRMED);
    }

    private Optional<MemoryItem> page(String name) {
        return pages().stream().filter(item -> name.equals(item.page())).findFirst();
    }

    private List<MemoryItem> claims(String name) {
        return ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && name.equals(item.page()) && item.live());
    }

    private Optional<MemoryEntry> entry(MemoryItem page) {
        Optional<MemoryType> type = MemoryType.fromPageType(page.attr(TYPE));
        if (type.isEmpty() || !MemoryFormat.isValidName(page.page())) {
            LOG.warn("skipping memory page {} with type {}", page.page(), page.attr(TYPE));
            return Optional.empty();
        }
        List<MemoryItem> claims = claims(page.page());
        String content = String.join("\n\n", claims.stream().map(MemoryItem::text).toList());
        Instant updated = claims.stream().map(MemoryItem::updated).reduce(page.updated(), (a, b) -> a.isAfter(b) ? a : b);
        String description = page.attr(DESCRIPTION) == null ? "" : page.attr(DESCRIPTION);
        return Optional.of(new MemoryEntry(page.page(), type.get(), description, content, updated));
    }
}
