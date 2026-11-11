package tantalum.shell.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.SkillList;
import tantalum.local.wire.WikiList;
import tantalum.shell.boot.Engine;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.LocalLinks.Reply;
import tantalum.shell.local.LocalWorkspace;
import tantalum.shell.skill.SkillCatalog;
import tantalum.shell.skill.SkillSettings;
import tantalum.value.id.Principal;
import tools.jackson.databind.JsonNode;

/**
 * 插件页的“技能”和“知识库”两项，和输入框的技能菜单。只看、只改当前登录用户自己的。两个开关照用
 * {@code PUT /api/plugins/local/skills|wiki}；这里管目录、单个技能的开关，和当场向电脑要来的清单。
 * <p>{@code state}：{@code off}（开关关着）、{@code unset}（没填目录）、{@code offline}（电脑没连上）、{@code old}（连接程序太旧）、
 * {@code late}（电脑没按时回）、{@code ok}、{@code missing}（目录还不存在）、{@code outside}（不在电脑允许的范围里）、
 * {@code unreadable}。
 */
@RestController
public class SkillController {

    private final Engine engine;
    private final WebJson json;

    public SkillController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    @GetMapping("/api/local/skills")
    ResponseEntity<String> skills() throws InterruptedException {
        return json.json(200, skillsView(WebJson.currentUser()));
    }

    /** 请求体 {@code {"dir": "…"}}，空串是清掉。回新的清单。 */
    @PutMapping("/api/local/skills")
    ResponseEntity<String> setSkillsDir(@RequestBody String body) throws InterruptedException {
        Principal user = WebJson.currentUser();
        Optional<String> dir = dir(body);
        if (dir.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"dir\": string}");
        }
        try {
            engine.skillSettings().setSkillsDir(user, dir.get());
        } catch (IllegalArgumentException invalid) {
            return json.error(ErrorCode.LOCAL_DIR_INVALID, invalid.getMessage());
        }
        return json.json(200, skillsView(user));
    }

    /** 请求体 {@code {"enabled": true|false}}：开关一个技能。名字只查写法，技能可以暂时不在目录里。 */
    @PutMapping("/api/local/skills/{name}")
    ResponseEntity<String> setSkill(@PathVariable String name, @RequestBody String body) {
        Optional<JsonNode> parsed = json.read(body);
        if (parsed.isEmpty() || !parsed.get().path("enabled").isBoolean()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"enabled\": true|false}");
        }
        boolean enabled = parsed.get().get("enabled").asBoolean();
        try {
            engine.skillSettings().setOff(WebJson.currentUser(), name, !enabled);
        } catch (IllegalArgumentException invalid) {
            return json.error(ErrorCode.REQUEST_INVALID, "not a skill name");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", name);
        response.put("enabled", enabled);
        return json.json(200, response);
    }

    @GetMapping("/api/local/wiki")
    ResponseEntity<String> wiki() throws InterruptedException {
        return json.json(200, wikiView(WebJson.currentUser()));
    }

    @PutMapping("/api/local/wiki")
    ResponseEntity<String> setWikiDir(@RequestBody String body) throws InterruptedException {
        Principal user = WebJson.currentUser();
        Optional<String> dir = dir(body);
        if (dir.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"dir\": string}");
        }
        try {
            engine.skillSettings().setWikiDir(user, dir.get());
        } catch (IllegalArgumentException invalid) {
            return json.error(ErrorCode.LOCAL_DIR_INVALID, invalid.getMessage());
        }
        return json.json(200, wikiView(user));
    }

    /**
     * 输入框的菜单：这个用户此刻能点名的技能，按名字排序。{@code manualOnly} 是只能这样点名、模型自己不会调的。
     * “技能”关着、没连上、没填目录时是空列表。电脑没按时回就用上一次的清单。
     */
    @GetMapping("/api/skills")
    ResponseEntity<String> menu() throws InterruptedException {
        Principal user = WebJson.currentUser();
        List<Map<String, Object>> items = new ArrayList<>();
        if (engine.localWorkspace().skillsDir(user).isPresent()) {
            SkillCatalog.Listing listing = engine.skillCatalog().fetch(user, null);
            Set<String> off = engine.skillSettings().off(user);
            if (listing.skills() != null) {
                for (SkillList.Item item : listing.skills().items()) {
                    if (item.user() && !off.contains(item.name())) {
                        Map<String, Object> view = new LinkedHashMap<>();
                        view.put("name", item.name());
                        view.put("description", item.description());
                        view.put("manualOnly", !item.model());
                        items.add(view);
                    }
                }
            }
        }
        return json.json(200, Map.of("skills", items));
    }

    private Optional<String> dir(String body) {
        return json.read(body).filter(node -> node.path("dir").isString()).map(node -> node.get("dir").asString());
    }

    private Map<String, Object> skillsView(Principal user) throws InterruptedException {
        SkillSettings settings = engine.skillSettings();
        Optional<String> dir = settings.skillsDir(user);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("dir", dir.orElse(""));
        List<Map<String, Object>> items = new ArrayList<>();
        List<SkillList.Problem> problems = List.of();
        String path = null;
        String state = blocked(user, LocalWorkspace.SKILLS, dir);
        if (state == null) {
            Reply<ContextResult> reply = engine.localLinks().context(user, null, dir.get(), null);
            SkillList list = reply.outcome() == Reply.Outcome.OK ? reply.result().skills() : null;
            state = state(reply, list == null ? null : list.state().name());
            if (list != null) {
                path = list.dir();
                problems = list.problems();
                Set<String> off = settings.off(user);
                for (SkillList.Item item : list.items()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", item.name());
                    row.put("description", item.description());
                    row.put("model", item.model());
                    row.put("user", item.user());
                    row.put("enabled", !off.contains(item.name()));
                    items.add(row);
                }
            }
        }
        view.put("state", state);
        view.put("path", path);
        view.put("items", items);
        view.put("problems", problems);
        return view;
    }

    private Map<String, Object> wikiView(Principal user) throws InterruptedException {
        Optional<String> dir = engine.skillSettings().wikiDir(user);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("dir", dir.orElse(""));
        String path = null;
        int listed = 0;
        int total = 0;
        boolean index = false;
        String state = blocked(user, LocalWorkspace.WIKI, dir);
        if (state == null) {
            Reply<ContextResult> reply = engine.localLinks().context(user, null, null, dir.get());
            WikiList list = reply.outcome() == Reply.Outcome.OK ? reply.result().wiki() : null;
            state = state(reply, list == null ? null : list.state().name());
            if (list != null) {
                path = list.dir();
                listed = Math.min(list.pages().size(), WikiList.MAX_PAGES);
                total = Math.max(list.total(), listed);
                index = list.index() != null;
            }
        }
        view.put("state", state);
        view.put("path", path);
        view.put("pages", listed);
        view.put("total", total);
        view.put("index", index);
        view.put("truncated", total > listed);
        return view;
    }

    /** 不用问电脑就知道结果的几种情况；要问时返回 null。 */
    private String blocked(Principal user, String switchName, Optional<String> dir) {
        Map<String, Boolean> switches = engine.localWorkspace().switches(user);
        if (!switches.get(LocalWorkspace.MASTER) || !switches.get(switchName)) {
            return "off";
        }
        if (dir.isEmpty()) {
            return "unset";
        }
        Optional<LocalLinks.Status> link = engine.localLinks().status(user);
        if (link.isEmpty()) {
            return "offline";
        }
        return link.get().hello().protocol() < LocalWorkspace.SKILL_LISTS ? "old" : null;
    }

    private static String state(Reply<ContextResult> reply, String listed) {
        return switch (reply.outcome()) {
            case NOT_CONNECTED -> "offline";
            case NO_ANSWER -> "late";
            case OK -> listed == null ? "unreadable" : listed.toLowerCase(Locale.ROOT);
        };
    }
}
