package tantalum.examples;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.value.id.Principal;

/** 只在端到端测试服务里注册：让测试能从服务端断开 e2e 用户的 SSE 连接，模拟断线。 */
@RestController
final class E2eController {

    private final Engine engine;

    E2eController(Engine engine) {
        this.engine = engine;
    }

    @PostMapping("/api/e2e/disconnect")
    String disconnect() {
        engine.hub().closeAll(new Principal(E2eServer.USER));
        return "{\"disconnected\":true}";
    }
}
