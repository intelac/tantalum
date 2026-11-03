package tantalum.shell.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.store.TurnQuery;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;

/** 回放 journal 得到的视图：每次模型调用的完整输入输出。只读，只能看自己的运行。轨迹在 {@link TraceController}。 */
@RestController
public class RunTraceController {

    private final Engine engine;
    private final WebJson json;

    public RunTraceController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    @GetMapping("/api/runs/{run}/turns")
    ResponseEntity<String> turns(@PathVariable String run) {
        Principal user = WebJson.currentUser();
        if (!owns(user, run)) {
            return json.error(404, "no such run");
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (TurnQuery.Turn turn : engine.queries().turns().turns(user, new RunId(run))) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("number", turn.number());
            summary.put("command", turn.command().value());
            summary.put("messages", turn.prompt().messages().size());
            summary.put("outcome", turn.reply() != null ? "replied" : turn.failure() != null ? "failed" : "pending");
            summaries.add(summary);
        }
        return json.json(200, Map.of("turns", summaries));
    }

    @GetMapping("/api/runs/{run}/turns/{number}")
    ResponseEntity<String> turn(@PathVariable String run, @PathVariable int number) {
        Principal user = WebJson.currentUser();
        if (!owns(user, run)) {
            return json.error(404, "no such run");
        }
        return engine.queries().turns().turns(user, new RunId(run)).stream()
                .filter(turn -> turn.number() == number).findFirst()
                .map(turn -> json.json(200, turn))
                .orElseGet(() -> json.error(404, "no such turn"));
    }

    private boolean owns(Principal user, String run) {
        return engine.queries().run(user, new RunId(run)).isPresent();
    }
}
