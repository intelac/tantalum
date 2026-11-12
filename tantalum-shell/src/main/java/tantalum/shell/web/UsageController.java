package tantalum.shell.web;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.value.id.Principal;
import tantalum.value.model.TokenUsage;

/** 当前登录用户自己的用量：不接受用户名参数，看别人的要走管理员的运维页。 */
@RestController
public class UsageController {

    private final Engine engine;
    private final WebJson json;

    public UsageController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    /**
     * {@code {day, input, output, cacheRead, cacheWrite, used, weighted?, limit?}}：今天（UTC）的用量。{@code used} 是算进额度的量
     * （{@code TokenUsage.counted()}：配了折算系数就是折算后的，否则是四项之和），和运维页的“今日用量”、每日额度插件判断用的是同一个数。
     * 服务器配了任何折算系数时 {@code weighted} 为 true，界面据此写明“折算”。{@code limit} 是开着的插件报的上限里最小的，没有插件报就没有这一项。
     */
    @GetMapping("/api/usage/today")
    ResponseEntity<String> today() throws SQLException {
        Principal user = WebJson.currentUser();
        LocalDate today = LocalDate.ofInstant(engine.clock().instant(), ZoneOffset.UTC);
        TokenUsage usage = engine.queries().usageOn(user, today);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("day", today.toString());
        view.put("input", usage.input());
        view.put("output", usage.output());
        view.put("cacheRead", usage.cacheRead());
        view.put("cacheWrite", usage.cacheWrite());
        view.put("used", usage.counted());
        if (engine.weights().any()) {
            view.put("weighted", true);
        }
        engine.plugins().dailyLimit(user).ifPresent(limit -> view.put("limit", limit));
        return json.json(200, view);
    }
}
