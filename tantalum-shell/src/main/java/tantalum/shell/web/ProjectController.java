package tantalum.shell.web;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.local.wire.BrowseResult;
import tantalum.shell.boot.Engine;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.project.Project;
import tantalum.shell.project.ProjectPaths;
import tantalum.shell.project.Projects;
import tantalum.value.id.Principal;
import tools.jackson.databind.JsonNode;

/** 当前用户登记的工作区：列、加、改名、删。别人的和不存在的一样是 404。 */
@RestController
public class ProjectController {

    private final Engine engine;
    private final WebJson json;

    public ProjectController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    @GetMapping("/api/projects")
    ResponseEntity<String> list() throws SQLException {
        List<Map<String, Object>> projects = engine.projects().list(WebJson.currentUser()).stream().map(ProjectController::describe).toList();
        return json.json(200, Map.of("projects", projects));
    }

    /** body：{@code {"path": "...", "title": "..."}}，名字可省。 */
    @PostMapping("/api/projects")
    ResponseEntity<String> add(@RequestBody String body) throws SQLException {
        Optional<JsonNode> input = json.read(body);
        if (input.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body is not valid JSON");
        }
        Principal user = WebJson.currentUser();
        String path = WebJson.text(input.get(), "path");
        // 电脑连着、认得列子目录时先问一次：不存在或不允许就不登记。没连上照样登记，控制台按当时的连接状态提示
        if (path != null && ProjectPaths.isAbsolute(path)) {
            LocalLinks.Listing listing = listing(user, path.strip());
            if (listing.outcome() == LocalLinks.Listing.Outcome.OK && listing.result().error() != null) {
                return json.error(ErrorCode.PROJECT_PATH_NOT_ALLOWED, "the computer does not have or allow this folder");
            }
        }
        try {
            return json.json(201, describe(engine.projects().add(user, path, WebJson.text(input.get(), "title"))));
        } catch (Projects.Refused refused) {
            return refused(refused);
        }
    }

    /** 添加框浏览电脑上的目录：不给 {@code path} 时列允许的目录本身。只列目录名。 */
    @GetMapping("/api/projects/browse")
    ResponseEntity<String> browse(@RequestParam(required = false) String path) {
        LocalLinks.Listing listing = listing(WebJson.currentUser(), path == null || path.isBlank() ? null : path.strip());
        return switch (listing.outcome()) {
            case NOT_CONNECTED -> json.error(ErrorCode.LOCAL_NOT_CONNECTED, "the computer is not connected");
            case OLD_PROTOCOL -> json.error(ErrorCode.LOCAL_PROTOCOL_OLD, "the connector is too old to list folders");
            case NO_ANSWER -> json.error(ErrorCode.LOCAL_NO_ANSWER, "the computer did not answer in time");
            case OK -> listing.result().error() != null
                    ? json.error(ErrorCode.PROJECT_PATH_NOT_ALLOWED, "the computer does not have or allow this folder")
                    : json.json(200, listed(listing.result()));
        };
    }

    private LocalLinks.Listing listing(Principal user, String path) {
        try {
            return engine.localLinks().browse(user, path);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            return new LocalLinks.Listing(LocalLinks.Listing.Outcome.NO_ANSWER, null);
        }
    }

    private static Map<String, Object> listed(BrowseResult result) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("path", result.path());
        view.put("dirs", result.dirs());
        view.put("truncated", result.truncated());
        return view;
    }

    /** body：{@code {"title": "..."}}。路径不能改。 */
    @PatchMapping("/api/projects/{id}")
    ResponseEntity<String> rename(@PathVariable String id, @RequestBody String body) throws SQLException {
        Optional<JsonNode> input = json.read(body);
        if (input.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body is not valid JSON");
        }
        try {
            return json.json(200, describe(engine.projects().rename(WebJson.currentUser(), id, WebJson.text(input.get(), "title"))));
        } catch (Projects.Refused refused) {
            return refused(refused);
        }
    }

    /** 只删登记；会话和电脑上的目录都不动。 */
    @DeleteMapping("/api/projects/{id}")
    ResponseEntity<String> delete(@PathVariable String id) throws SQLException {
        return engine.projects().delete(WebJson.currentUser(), id)
                ? ResponseEntity.noContent().build()
                : json.error(ErrorCode.PROJECT_NOT_FOUND, "no such project");
    }

    private ResponseEntity<String> refused(Projects.Refused refused) {
        return switch (refused.problem()) {
            case PATH_INVALID -> json.error(ErrorCode.PROJECT_PATH_INVALID, "path must be absolute");
            case PATH_TAKEN -> json.error(ErrorCode.PROJECT_PATH_TAKEN, "this folder is already a project");
            case TITLE_INVALID -> json.error(ErrorCode.PROJECT_TITLE_INVALID, "title must be 1 to " + Projects.MAX_TITLE + " characters",
                    Map.of("max", Projects.MAX_TITLE));
            case TITLE_TAKEN -> json.error(ErrorCode.PROJECT_TITLE_TAKEN, "a project with this title exists");
            case NOT_FOUND -> json.error(ErrorCode.PROJECT_NOT_FOUND, "no such project");
        };
    }

    static Map<String, Object> describe(Project project) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", project.id());
        view.put("title", project.title());
        view.put("path", project.path());
        view.put("created", project.created().toString());
        return view;
    }
}
