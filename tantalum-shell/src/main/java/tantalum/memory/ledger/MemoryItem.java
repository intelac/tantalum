package tantalum.memory.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 记忆账上的一条（memory-final-plan 第 4 节）。每次修改是一条新版本（{@code version} 加一），旧版本留在账上。
 * 字段在 M1 一次定死；后面阶段才用的先留空（null 或空表）。
 *
 * @param id 稳定编号
 * @param version 第几版，从 1 起
 * @param state 待定 / 确认 / 撤掉：工具写入先待定，对应的工具结果落进 journal 才确认
 * @param kind 哪一类
 * @param page 说法所属的页面（{@link Kind#CLAIM}）或页面自己的名字（{@link Kind#PAGE}）；别的为 null
 * @param scope {@code personal}、{@code project:<键>}；预留 {@code team:<id>}、{@code company}
 * @param source 这条是从哪段文字来的
 * @param quote 一句原文引用，代码核对过；没有为 null
 * @param provenance 指回 journal 的哪一步；没有（如迁移来的、你在控制台写的）为 null
 * @param actor 谁写的
 * @param validFrom 从何时起有效
 * @param supersededBy 被哪条取代，没被取代为 null
 * @param lastConfirmed 上次确认的时间，没有为 null
 * @param confidence 观察到 / 推断 / 认证过
 * @param derivedFrom 从哪些条目来的
 * @param status 只有决定有；别的为 null
 * @param pendingConfirm 等你点“确认 / 撤销”
 * @param text 正文
 * @param attrs 各类自己的字段（页面的类型和描述、当天卡的日期和会话、工作记忆的状态和期限……），键见各类的说明
 * @param owner 预留：团队、公司层的归属
 * @param visibleTo 预留：团队、公司层的可见范围
 * @param certifiedHash 预留：认证时那一版的哈希
 * @param consent 预留：本人同意上行的记录
 * @param created 第一版的时间
 * @param updated 这一版的时间
 */
public record MemoryItem(String id, int version, State state, Kind kind, String page, String scope, Source source, String quote,
                         Provenance provenance, Actor actor, Instant validFrom, String supersededBy, Instant lastConfirmed,
                         Confidence confidence, List<String> derivedFrom, Status status, boolean pendingConfirm, String text,
                         Map<String, String> attrs, String owner, List<String> visibleTo, String certifiedHash, String consent,
                         Instant created, Instant updated) {

    public static final String PERSONAL = "personal";
    public static final String PROJECT_PREFIX = "project:";

    public MemoryItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(created, "created");
        Objects.requireNonNull(updated, "updated");
        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1");
        }
        derivedFrom = derivedFrom == null ? List.of() : List.copyOf(derivedFrom);
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
        visibleTo = visibleTo == null ? List.of() : List.copyOf(visibleTo);
        validFrom = validFrom == null ? created : validFrom;
    }

    /** 一条新的（第 1 版），其余字段取默认值，用 {@code with…} 补。 */
    public static MemoryItem create(String id, Kind kind, Source source, Actor actor, String text, Instant at) {
        Confidence confidence = source == Source.USER_SAID ? Confidence.OBSERVED : Confidence.INFERRED;
        return new MemoryItem(id, 1, State.CONFIRMED, kind, null, PERSONAL, source, null, null, actor, at, null, null, confidence,
                List.of(), null, false, text, Map.of(), null, List.of(), null, null, at, at);
    }

    public String attr(String key) {
        return attrs.get(key);
    }

    /** 在账上算不算“有效”：确认过、没被取代。墓碑另算（见 {@link Ledger}）。 */
    public boolean live() {
        return state == State.CONFIRMED && supersededBy == null;
    }

    public MemoryItem withPage(String value) {
        return new MemoryItem(id, version, state, kind, value, scope, source, quote, provenance, actor, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withScope(String value) {
        return new MemoryItem(id, version, state, kind, page, value, source, quote, provenance, actor, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withState(State value) {
        return new MemoryItem(id, version, value, kind, page, scope, source, quote, provenance, actor, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withQuote(String value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, value, provenance, actor, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withProvenance(Provenance value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, quote, value, actor, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withSupersededBy(String value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, quote, provenance, actor, validFrom, value,
                lastConfirmed, confidence, derivedFrom, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withLastConfirmed(Instant value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, quote, provenance, actor, validFrom, supersededBy,
                value, confidence, derivedFrom, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withConfidence(Confidence value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, quote, provenance, actor, validFrom, supersededBy,
                lastConfirmed, value, derivedFrom, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withDerivedFrom(List<String> value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, quote, provenance, actor, validFrom, supersededBy,
                lastConfirmed, confidence, value, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withStatus(Status value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, quote, provenance, actor, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, value, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withPendingConfirm(boolean value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, quote, provenance, actor, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, status, value, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withText(String value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, quote, provenance, actor, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, status, pendingConfirm, value, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withAttrs(Map<String, String> value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, quote, provenance, actor, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, status, pendingConfirm, text, value, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    public MemoryItem withActor(Actor value) {
        return new MemoryItem(id, version, state, kind, page, scope, source, quote, provenance, value, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                created, updated);
    }

    /** 账写入时用：下一版的版本号和时间。 */
    MemoryItem nextVersion(int value, Instant at) {
        return new MemoryItem(id, value, state, kind, page, scope, source, quote, provenance, actor, validFrom, supersededBy,
                lastConfirmed, confidence, derivedFrom, status, pendingConfirm, text, attrs, owner, visibleTo, certifiedHash, consent,
                value == 1 ? at : created, at);
    }

    /** 哪一类。 */
    public enum Kind {
        /** 工作记忆：在做的事。attrs：title、status（active / waiting / done / dropped）、session、next。 */
        TASK,
        /** 工作记忆：答应的事。attrs：to、due、by（agent / user）。 */
        COMMITMENT,
        /** 工作记忆：在等的事。attrs：on（等谁）、since、session。 */
        WAITING,
        /** 工作记忆：最近注意到的。 */
        NOTICED,
        /** 当天卡的一段（一轮的要点）。attrs：day、session、title、run、heading。 */
        CARD,
        /** 页面。attrs：type（person / project / decision / preference / procedure / term）、description。page 是页面名。 */
        PAGE,
        /** 页面上的一条说法。page 是所属页面。 */
        CLAIM
    }

    public enum State { PENDING, CONFIRMED, WITHDRAWN }

    public enum Source { USER_SAID, ASSISTANT_INFERRED, TOOL_FILE, TOOL_WEB, TOOL_OTHER, OTHER_PERSON, LEGACY }

    public enum Actor { USER, AGENT, LIBRARIAN, CHILD, MIGRATION }

    public enum Confidence { OBSERVED, INFERRED, CERTIFIED }

    public enum Status { PROPOSED, APPROVED, VOID }

    /** 指回 journal：会话、运行、命令、步（序号）；不知道的为 null。 */
    public record Provenance(String session, String run, String command, Long step) {}
}
