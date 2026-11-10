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
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.project.Project;
import tantalum.shell.project.Projects;
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
        try {
            Project added = engine.projects().add(WebJson.currentUser(), WebJson.text(input.get(), "path"), WebJson.text(input.get(), "title"));
            return json.json(201, describe(added));
        } catch (Projects.Refused refused) {
            return refused(refused);
        }
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
