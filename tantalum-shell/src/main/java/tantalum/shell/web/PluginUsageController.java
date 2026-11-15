package tantalum.shell.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.boot.PlatformServices;
import tantalum.shell.store.PluginUsage;

/**
 * 插件调模型的用量（memory-final-plan 第 15 节），只给管理员（{@code /api/admin/**}）：最近几天每个人、每个插件、每种用途的调用次数、
 * 失败次数和 token。不算每日额度。
 */
@RestController
public class PluginUsageController {

    private final Engine engine;
    private final PlatformServices services;
    private final WebJson json;

    public PluginUsageController(Engine engine, PlatformServices services, WebJson json) {
        this.engine = engine;
        this.services = services;
        this.json = json;
    }

    /** {@code days} 是 1 到 90，默认 7：{@code {days, totals: [{user, plugin, purpose, calls, failures, input, output}]}}。 */
    @GetMapping("/api/admin/plugin-usage")
    ResponseEntity<String> usage(@RequestParam(name = "days", defaultValue = "7") int days) {
        int clamped = Math.max(1, Math.min(90, days));
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PluginUsage.Total total : services.usage(engine).totals(Instant.now().minus(Duration.ofDays(clamped)))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("user", total.user());
                row.put("plugin", total.plugin());
                row.put("purpose", total.purpose());
                row.put("calls", total.calls());
                row.put("failures", total.failures());
                row.put("input", total.input());
                row.put("output", total.output());
                rows.add(row);
            }
            return json.json(200, Map.of("days", clamped, "totals", rows));
        } catch (java.io.IOException | java.sql.SQLException failure) {
            return json.error(ErrorCode.SERVER_BUSY, "the plugin usage cannot be read now");
        }
    }
}
