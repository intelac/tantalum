package tantalum.shell.web;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 前端路由回退到 {@code index.html}，路径由浏览器端解析。{@code /api} 和 {@code /assets} 不经过这里。 */
@Controller
public class SpaController {

    private final Resource index = new ClassPathResource("static/index.html");

    @GetMapping({"/", "/login", "/s/**"})
    ResponseEntity<Resource> index() {
        if (!index.exists()) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN)
                    .body(new ByteArrayResource("console not built: run pnpm build in console/".getBytes(StandardCharsets.UTF_8)));
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(index);
    }
}
