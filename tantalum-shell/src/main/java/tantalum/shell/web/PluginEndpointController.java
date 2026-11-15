package tantalum.shell.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.auth.ViewAs;
import tantalum.shell.boot.Engine;
import tantalum.shell.plugin.PluginEndpoints.PluginRequest;
import tantalum.shell.plugin.PluginEndpoints.PluginResponse;
import tantalum.shell.plugin.PluginEndpoints;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * 插件的接口（memory-plugin 需求 1.5）：{@code /api/plugin-api/<插件>/<路径>} 交给那个插件的 {@link PluginEndpoints}。
 * 插件没装、对这个用户关着时 404；管理员只读查看时只有 GET、且插件说能看的路径才交过去，其余回 {@code view/read-only}。
 */
@RestController
public class PluginEndpointController {

    static final String PREFIX = "/api/plugin-api/";
    private static final Logger LOG = LoggerFactory.getLogger(PluginEndpointController.class);

    private final Engine engine;
    private final WebJson json;

    public PluginEndpointController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    @RequestMapping(path = PREFIX + "{plugin}/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    ResponseEntity<String> handle(@PathVariable String plugin, @RequestParam Map<String, String> query,
                                  @RequestBody(required = false) String body, HttpServletRequest request) {
        Principal user = WebJson.currentUser();
        Optional<PluginEndpoints> endpoints = engine.members(PluginEndpoints.class).stream()
                .filter(each -> each.plugin().equals(plugin)).findFirst();
        if (endpoints.isEmpty() || !engine.plugins().pluginEnabled(user, plugin)) {
            return json.error(404, ErrorCode.PLUGIN_NOT_FOUND, "no such plugin endpoint", Map.of());
        }
        List<String> path = path(request.getRequestURI(), request.getContextPath(), plugin);
        boolean viewing = SecurityContextHolder.getContext().getAuthentication() instanceof ViewAs.Viewing;
        if (viewing && (!request.getMethod().equals("GET") || !endpoints.get().viewable(path))) {
            return json.error(ErrorCode.VIEW_READ_ONLY, "view_read_only");
        }
        Json parsed = null;
        if (body != null && !body.isBlank()) {
            Optional<JsonNode> node = json.read(body);
            if (node.isEmpty()) {
                return json.error(ErrorCode.REQUEST_INVALID, "body is not valid JSON");
            }
            parsed = json.value(node.get());
        }
        Map<String, String> params = new LinkedHashMap<>(query);
        params.remove(ViewAs.PARAM);
        PluginResponse response;
        try {
            response = endpoints.get().handle(new PluginRequest(request.getMethod(), path, params, parsed, user, viewing));
        } catch (RuntimeException failure) {
            LOG.warn("plugin {} endpoint failed: {}", plugin, failure.toString());
            return json.error(500, ErrorCode.SERVER_BUSY, "the plugin failed; try again");
        }
        return response.body() == null ? ResponseEntity.status(response.status()).build() : json.json(response.status(), response.body());
    }

    /** 插件名后面的部分，按 / 分开、解码；空段去掉。 */
    static List<String> path(String uri, String context, String plugin) {
        String rest = uri.substring(context.length() + PREFIX.length() + plugin.length());
        List<String> parts = new ArrayList<>();
        for (String part : rest.split("/")) {
            if (!part.isEmpty()) {
                parts.add(URLDecoder.decode(part, StandardCharsets.UTF_8));
            }
        }
        return parts;
    }
}
