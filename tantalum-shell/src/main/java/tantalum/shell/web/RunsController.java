package tantalum.shell.web;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.store.RunQuery;
import tantalum.shell.store.RunTable;
import tantalum.shell.store.SessionQueries;
import tantalum.shell.trace.RunRow;
import tantalum.shell.trace.RunTree;
import tantalum.shell.trace.Tally;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.EndReason;

/**
 * 运行（run-tree 需求 1、4）：一次运行的调用树，和当前用户跨会话的运行列表。只读；只能看自己的，别人的和不存在一样。
 * 管理员只读查看时“当前用户”是被查看的人，和其他接口相同。没有值的字段不写出。
 */
@RestController
public class RunsController {

    private static final Logger LOG = LoggerFactory.getLogger(RunsController.class);
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final Engine engine;
    private final WebJson json;

    public RunsController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    @GetMapping("/api/sessions/{session}/runs/{run}/tree")
    ResponseEntity<String> tree(@PathVariable String session, @PathVariable String run) {
        Optional<SessionId> id = ApiController.sessionId(session);
        if (id.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid session id");
        }
        RunId target = ApiController.runId(run).orElse(null);
        if (target == null || target.seq() < 0) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid run id");
        }
        if (!target.session().map(id.get()::equals).orElse(false)) {
            return json.error(ErrorCode.RUN_NOT_FOUND, "no such run");
        }
        return engine.queries().runs().tree(WebJson.currentUser(), id.get(), target)
                .map(found -> json.json(200, Map.of("root", node(found.root()))))
                .orElseGet(() -> json.error(ErrorCode.RUN_NOT_FOUND, "no such run"));
    }

    @GetMapping("/api/runs")
    ResponseEntity<String> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                @RequestParam(required = false) List<String> end, @RequestParam(required = false) String toolErrors,
                                @RequestParam(required = false) String tool, @RequestParam(required = false) String model,
                                @RequestParam(required = false) String session, @RequestParam(required = false) String sort,
                                @RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
        RunTable.Filter filter;
        try {
            filter = new RunTable.Filter(instant(from), instant(to), ends(end), bool(toolErrors), blankToNull(tool), blankToNull(model),
                    session == null ? null : ApiController.sessionId(session).orElseThrow(() -> new IllegalArgumentException("invalid session id")),
                    sort(sort), blankToNull(cursor), limit(limit));
        } catch (IllegalArgumentException invalid) {
            return json.error(ErrorCode.REQUEST_INVALID, invalid.getMessage());
        }
        RunTable.Page page;
        try {
            page = engine.queries().runs().list(WebJson.currentUser(), filter);
        } catch (IllegalArgumentException invalid) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid cursor");
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        for (RunTable.Listed listed : page.runs()) {
            runs.add(row(listed));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runs", runs);
        body.put("next", page.next());
        return json.json(200, body);
    }

    /** 当前筛选条件下的汇总（observe-page 需求 5）：参数和列表相同（没有游标、排序、条数），{@code tz} 是划分天用的时区。 */
    @GetMapping("/api/runs/summary")
    ResponseEntity<String> summary(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                   @RequestParam(required = false) List<String> end, @RequestParam(required = false) String toolErrors,
                                   @RequestParam(required = false) String tool, @RequestParam(required = false) String model,
                                   @RequestParam(required = false) String session, @RequestParam(required = false) String tz) {
        RunTable.Filter filter;
        ZoneId zone;
        try {
            filter = new RunTable.Filter(instant(from), instant(to), ends(end), bool(toolErrors), blankToNull(tool), blankToNull(model),
                    session == null ? null : ApiController.sessionId(session).orElseThrow(() -> new IllegalArgumentException("invalid session id")),
                    RunTable.Sort.STARTED, null, DEFAULT_LIMIT);
            zone = zone(tz);
        } catch (IllegalArgumentException invalid) {
            return json.error(ErrorCode.REQUEST_INVALID, invalid.getMessage());
        }
        RunTable.Summary summary = engine.queries().runs().summary(WebJson.currentUser(), filter, zone);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runs", summary.runs());
        body.put("ends", summary.ends());
        body.put("toolErrorRuns", summary.toolErrorRuns());
        body.put("tokens", summary.tokens());
        put(body, "p50Ms", summary.p50Ms());
        put(body, "p90Ms", summary.p90Ms());
        body.put("days", summary.days().stream().map(day -> Map.of("day", day.day().toString(), "ends", day.ends(), "tokens", day.tokens())).toList());
        body.put("tools", summary.tools());
        body.put("models", summary.models());
        return json.json(200, body);
    }

    private static ZoneId zone(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("invalid tz: use an IANA zone like Asia/Tokyo");
        }
    }

    @ExceptionHandler(SessionQueries.SessionUnreadable.class)
    ResponseEntity<String> unreadable(SessionQueries.SessionUnreadable failure) {
        LOG.error("session unreadable", failure);
        return json.error(ErrorCode.SESSION_UNREADABLE, "session unreadable");
    }

    @ExceptionHandler(RunQuery.RunsUnavailable.class)
    ResponseEntity<String> unavailable(RunQuery.RunsUnavailable failure) {
        LOG.error("run index unavailable", failure);
        return json.error(503, ErrorCode.SESSION_UNAVAILABLE, "run index unavailable", Map.of());
    }

    static Map<String, Object> node(RunTree.Node node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", node.id());
        put(out, "parent", node.parent());
        out.put("kind", node.kind().name().toLowerCase(Locale.ROOT));
        put(out, "name", node.name());
        put(out, "start", node.start());
        put(out, "end", node.end());
        put(out, "ms", node.ms());
        out.put("state", node.state().name().toLowerCase(Locale.ROOT));
        put(out, "detail", node.detail());
        put(out, "record", node.record());
        put(out, "firstToken", node.firstToken());
        if (node.tally() != null) {
            out.put("tally", tally(node.tally()));
        }
        if (!node.children().isEmpty()) {
            out.put("children", node.children().stream().map(RunsController::node).toList());
        }
        return out;
    }

    static Map<String, Object> row(RunTable.Listed listed) {
        RunRow row = listed.row();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", row.session().value());
        out.put("title", listed.title());
        out.put("run", row.run().value());
        put(out, "turn", row.turn());
        out.put("trigger", row.trigger().name());
        out.put("start", row.start());
        put(out, "end", row.end());
        put(out, "endReason", row.endReason() == null ? null : row.endReason().name());
        put(out, "prompt", row.prompt());
        out.put("tally", tally(row.tally()));
        return out;
    }

    static Map<String, Object> tally(Tally tally) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tokens", usage(tally.tokens()));
        out.put("summaryTokens", usage(tally.summaryTokens()));
        put(out, "context", tally.context());
        out.put("requests", tally.requests());
        out.put("attempts", tally.attempts());
        out.put("retries", tally.retries());
        put(out, "modelMs", tally.modelMs());
        put(out, "ttftMs", tally.ttftMs());
        out.put("toolCalls", tally.toolCalls());
        out.put("toolErrors", tally.toolErrors());
        put(out, "toolMs", tally.toolMs());
        out.put("tools", tally.tools().stream().map(count -> Map.of("name", count.name(), "calls", count.calls(), "errors", count.errors())).toList());
        out.put("hooks", tally.hooks());
        out.put("hookErrors", tally.hookErrors());
        out.put("models", tally.models());
        put(out, "ms", tally.ms());
        return out;
    }

    private static Map<String, Object> usage(TokenUsage usage) {
        return Map.of("input", usage.input(), "output", usage.output(), "cacheRead", usage.cacheRead(), "cacheWrite", usage.cacheWrite());
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value instanceof Instant instant ? instant.toString() : value);
        }
    }

    private static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("invalid time: use ISO-8601, like 2026-11-15T00:00:00Z");
        }
    }

    private static Set<String> ends(List<String> raw) {
        if (raw == null) {
            return null;
        }
        Set<String> ends = new LinkedHashSet<>();
        for (String joined : raw) {
            for (String part : joined.split(",")) {
                String value = part.strip().toUpperCase(Locale.ROOT);
                if (value.isEmpty()) {
                    continue;
                }
                if (!value.equals(RunTable.RUNNING)) {
                    try {
                        EndReason.valueOf(value);
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalArgumentException("invalid end: " + part.strip());
                    }
                }
                ends.add(value);
            }
        }
        return ends.isEmpty() ? null : ends;
    }

    private static Boolean bool(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("toolErrors must be true or false");
        };
    }

    private static RunTable.Sort sort(String raw) {
        if (raw == null || raw.isBlank()) {
            return RunTable.Sort.STARTED;
        }
        return switch (raw) {
            case "started" -> RunTable.Sort.STARTED;
            case "ms" -> RunTable.Sort.MS;
            case "tokens" -> RunTable.Sort.TOKENS;
            default -> throw new IllegalArgumentException("sort must be started, ms or tokens");
        };
    }

    private static int limit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be 1 to " + MAX_LIMIT);
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("limit must be 1 to " + MAX_LIMIT);
        }
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
