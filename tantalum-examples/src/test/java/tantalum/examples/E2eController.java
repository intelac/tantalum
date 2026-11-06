package tantalum.examples;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/** 只在端到端测试服务里注册：让测试能从服务端断开 e2e 用户的 SSE 连接，模拟断线；能看一个会话是否还在内存里；能换访问范围规则。 */
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

    /** 换访问范围规则（{@link E2eScopeHook}）：相当于部署了一条新规则，之后的步骤按它来。 */
    @PostMapping("/api/e2e/scope/{blocking}")
    String scope(@PathVariable boolean blocking) {
        E2eScopeHook.BLOCKING.set(blocking);
        return "{\"blocking\":" + blocking + "}";
    }

    /**
     * 起一个真的连接程序（local-workspace 任务 19），请求体 {@code {"server": …, "token": …}}，令牌交给子进程的环境变量。
     * 浏览器端的测试代码不能起进程，所以由这个只在测试里注册的接口代劳。
     */
    @PostMapping("/api/e2e/computer")
    java.util.Map<String, Object> startComputer(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, String> body)
            throws java.io.IOException {
        E2eComputer.start(body.get("server"), body.get("token"));
        return computer();
    }

    /** 连接程序还在不在跑、退出码、电脑上 {@code a.txt} 的内容、终端输出（看有没有漏出令牌）。 */
    @GetMapping("/api/e2e/computer")
    java.util.Map<String, Object> computer() throws java.io.IOException {
        E2eComputer computer = E2eComputer.current();
        if (computer == null) {
            return java.util.Map.of("running", false);
        }
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("running", computer.running());
        view.put("exitCode", computer.exitCode());
        view.put("file", computer.file());
        view.put("output", computer.output());
        return view;
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/e2e/computer")
    java.util.Map<String, Object> stopComputer() throws java.io.IOException {
        E2eComputer.stop();
        return java.util.Map.of("running", false);
    }

    @GetMapping("/api/e2e/resident/{session}")
    String resident(@PathVariable String session) {
        return "{\"resident\":" + engine.state().sessions().containsKey(new SessionId(session)) + "}";
    }
}
