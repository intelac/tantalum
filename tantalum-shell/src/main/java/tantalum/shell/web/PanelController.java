package tantalum.shell.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.panel.Panel;

/**
 * 插件面板（plugin-panel 需求 1–3）：{@code /panels/<ID>/**} 给插件 jar 里的面板文件，{@code /api/panels} 列出这个人能用的面板。
 * 面板文件是静态的、不带用户数据，不用登录；响应带严格的 CSP（不能联网、不能嵌框架和提交表单），控制台用沙箱 iframe 打开。
 * 沙箱里是不透明的源，模块脚本算跨源请求，所以给 {@code Access-Control-Allow-Origin: *}。
 */
@RestController
public class PanelController {

    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            + "font-src 'self' data:; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'self'";

    private static final Map<String, MediaType> TYPES = Map.ofEntries(
            Map.entry("html", MediaType.parseMediaType("text/html;charset=UTF-8")),
            Map.entry("js", MediaType.parseMediaType("text/javascript;charset=UTF-8")),
            Map.entry("mjs", MediaType.parseMediaType("text/javascript;charset=UTF-8")),
            Map.entry("css", MediaType.parseMediaType("text/css;charset=UTF-8")),
            Map.entry("json", MediaType.APPLICATION_JSON),
            Map.entry("svg", MediaType.parseMediaType("image/svg+xml")),
            Map.entry("png", MediaType.IMAGE_PNG),
            Map.entry("jpg", MediaType.IMAGE_JPEG),
            Map.entry("webp", MediaType.parseMediaType("image/webp")),
            Map.entry("woff2", MediaType.parseMediaType("font/woff2")));

    private final Engine engine;
    private final WebJson json;

    public PanelController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    @GetMapping("/api/panels")
    ResponseEntity<String> list() {
        var user = WebJson.currentUser();
        List<Map<String, Object>> panels = engine.panels().list(plugin -> engine.plugins().pluginEnabled(user, plugin)).stream()
                .map(panel -> Map.<String, Object>of("name", panel.name(), "title", panel.title(), "icon", panel.icon(), "url", panel.url()))
                .toList();
        return json.json(200, Map.of("panels", panels));
    }

    @GetMapping("/panels/{name}/**")
    ResponseEntity<Resource> file(@PathVariable String name, HttpServletRequest request) {
        Optional<Panel> panel = engine.panels().find(name);
        String prefix = "/panels/" + name + "/";
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (panel.isEmpty() || !path.startsWith(prefix)) {
            return ResponseEntity.notFound().build();
        }
        String relative = path.substring(prefix.length());
        MediaType type = type(relative);
        if (type == null || !safe(relative)) {
            return ResponseEntity.notFound().build();
        }
        ClassPathResource resource = new ClassPathResource(panel.get().base() + relative, Engine.class.getClassLoader());
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header("Content-Security-Policy", CSP)
                .header("X-Content-Type-Options", "nosniff")
                .header("Access-Control-Allow-Origin", "*")
                .header("Cache-Control", "no-cache")
                .body(resource);
    }

    /** 只允许普通的相对路径：没有空段、{@code .}、{@code ..}、反斜杠和编码过的字符。 */
    static boolean safe(String relative) {
        if (relative.isEmpty() || relative.contains("\\") || relative.contains("%") || relative.startsWith("/")) {
            return false;
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static MediaType type(String relative) {
        int dot = relative.lastIndexOf('.');
        return dot < 0 ? null : TYPES.get(relative.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
