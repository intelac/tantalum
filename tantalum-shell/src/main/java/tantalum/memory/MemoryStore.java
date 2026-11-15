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
        /** 放着等你确认（{@link #writePending}）。 */
        record Pending(MemoryItem claim) implements WriteResult {}

        record Created(MemoryEntry entry) implements WriteResult {}
        record Updated(MemoryEntry entry) implements WriteResult {}
        /** {@code reason} 是英文，原样给模型（memory_write 的结果）；{@code problem} 和 {@code params} 给控制台按语言说。 */
        record Rejected(Problem problem, String reason, Map<String, Object> params) implements WriteResult {}
    }

    /** 拒绝的种类。 */
    public enum Problem { INVALID_NAME, RESERVED_NAME, INVALID, TOO_LONG, FULL, INDEX_FULL, CREDENTIAL, CONFLICT }

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

    /** 条目现在的版本（页面的版本，每写一次加一）；不存在为 0。 */
    public int version(String name) {
        return MemoryFormat.isValidName(name) ? page(name).map(MemoryItem::version).orElse(0) : 0;
    }

    /** 新建或同名覆盖。校验、凭据检查和上限都在这里，调用方只需转达 {@code Rejected.reason()}。 */
    public WriteResult write(String name, String type, String description, String content, MemoryItem.Actor actor,
                             MemoryItem.Source source) {
        return write(name, type, description, content, actor, source, null);
    }

    /**
     * 同上；{@code expectedVersion} 不为 null 时先比对版本（memory-final-plan 第 4 节：两个地方同时改不会悄悄覆盖），
     * 对不上就拒绝（{@link Problem#CONFLICT}），参数里带现在的版本。新建时版本是 0。
     */
    public WriteResult write(String name, String type, String description, String content, MemoryItem.Actor actor,
                             MemoryItem.Source source, Integer expectedVersion) {
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
            if (expectedVersion != null && expectedVersion != version(name)) {
                return new WriteResult.Rejected(Problem.CONFLICT, "the entry was changed elsewhere; it is now version " + version(name)
                        + ". Read it again and redo the change", Map.of("version", version(name)));
            }
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
            } else {
                // 每写一次页面的版本加一，条目的版本就是它
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

    /** 这个条目上你认可过的决定（{@code status = approved}）；没有为空。 */
    public Optional<MemoryItem> approved(String name) {
        if (!MemoryFormat.isValidName(name)) {
            return Optional.empty();
        }
        return claims(name).stream().filter(item -> item.status() == MemoryItem.Status.APPROVED).findFirst();
    }

    /** 待确认的整条改写（确认后取代这个条目上原来的说法），attrs 里的 {@value #REPLACES} 标明。 */
    public static final String REPLACES = "replaces";
    /** 和哪条认可过的决定冲突。 */
    public static final String CONFLICTS_WITH = "conflictsWith";

    /**
     * 模型的改写先放着等你确认（memory-final-plan 第 7 节：读过外部内容的一轮里写的；第 4 节：认可过的决定只能由你改）。
     * 校验和 {@link #write} 一样；不取代任何东西，确认后才取代。{@code conflictsWith} 是冲突的那条决定（没有为 null）。
     */
    public WriteResult writePending(String name, String type, String description, String content, MemoryItem.Source source,
                                    MemoryItem.Provenance provenance, String conflictsWith) {
        Optional<WriteResult.Rejected> invalid = MemoryFormat.validate(name, type, description, content, limits.maxEntryBytes());
        if (invalid.isPresent()) {
            return invalid.get();
        }
        if (CredentialGuard.detect(description, content).isPresent()) {
            return new WriteResult.Rejected(Problem.CREDENTIAL, CredentialGuard.REASON, Map.of());
        }
        MemoryType memoryType = MemoryType.parse(type).orElseThrow();
        Map<String, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put(REPLACES, "page");
        if (conflictsWith != null) {
            attrs.put(CONFLICTS_WITH, conflictsWith);
        }
        MemoryItem claim = MemoryItem.create(ledger.newId(), MemoryItem.Kind.CLAIM, source, MemoryItem.Actor.AGENT, content,
                ledger.clock().instant()).withProvenance(provenance).withPendingConfirm(true).withAttrs(attrs);
        Optional<MemoryItem> added = addClaim(name, memoryType.pageType(), description.trim(), claim);
        if (added.isEmpty()) {
            return new WriteResult.Rejected(Problem.INVALID, "the same text is already waiting for confirmation or in memory", Map.of());
        }
        return new WriteResult.Pending(added.get());
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

    /** 等你确认的说法（外部来源），最近的在前。 */
    public List<MemoryItem> pendingClaims() {
        return ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && item.state() == MemoryItem.State.CONFIRMED && item.pendingConfirm()
                && item.supersededBy() == null).stream().sorted(Comparator.comparing(MemoryItem::created).reversed()).toList();
    }

    private List<MemoryItem> pages() {
        return ledger.items(item -> item.kind() == MemoryItem.Kind.PAGE && item.state() == MemoryItem.State.CONFIRMED && !item.pendingConfirm());
    }

    /**
     * 在页面上加一条说法，不取代别的（捕获和 memory_note 用，memory-final-plan 第 7 节）。页面不存在时先建（待确认的说法建的页面也待确认）；
     * 页面上已经有意思一样的生效说法（规范化后相同）就不写。返回写下的说法；没写为空。名字不合规矩、条目满了也不写。
     */
    public Optional<MemoryItem> addClaim(String name, String pageType, String description, MemoryItem claim) {
        if (!MemoryFormat.isValidName(name) || MemoryType.fromPageType(pageType).isEmpty()) {
            return Optional.empty();
        }
        synchronized (this) {
            String same = tantalum.memory.capture.Quotes.normalize(claim.text());
            boolean duplicate = ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && name.equals(item.page())
                    && (item.live() || item.pendingConfirm() && item.state() == MemoryItem.State.CONFIRMED))
                    .stream().anyMatch(item -> tantalum.memory.capture.Quotes.normalize(item.text()).equals(same));
            if (duplicate) {
                return Optional.empty();
            }
            Optional<MemoryItem> page = ledger.items(item -> item.kind() == MemoryItem.Kind.PAGE && item.state() == MemoryItem.State.CONFIRMED
                    && name.equals(item.page())).stream().findFirst();
            if (page.isEmpty()) {
                if (list().size() >= limits.maxEntries()) {
                    LOG.info("memory is full; a captured note for page {} was not kept", name);
                    return Optional.empty();
                }
                ledger.put(MemoryItem.create(ledger.newId(), MemoryItem.Kind.PAGE, claim.source(), claim.actor(), "", ledger.clock().instant())
                        .withPage(name).withAttrs(Map.of(TYPE, pageType, DESCRIPTION, description))
                        .withPendingConfirm(claim.pendingConfirm()), 0);
            } else if (page.get().pendingConfirm() && !claim.pendingConfirm()) {
                ledger.put(page.get().withPendingConfirm(false), page.get().version());
            }
            Ledger.Result written = ledger.put(claim.withPage(name), 0);
            return written instanceof Ledger.Result.Written done ? Optional.of(done.item()) : Optional.empty();
        }
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
