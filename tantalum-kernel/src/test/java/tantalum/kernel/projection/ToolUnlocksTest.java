package tantalum.kernel.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.model.ToolSpec;
import tantalum.value.model.ToolUnlock;
import tantalum.value.state.SessionContext;

/** browser-on-demand：要加哪些、重新冻结时（压缩后）留哪些。 */
class ToolUnlocksTest {

    private static final ToolUnlock RULE = new ToolUnlock(List.of("browser_navigate", "browser_tabs"), List.of("browser_type", "browser_click"));
    private static final List<ToolSpec> ALL = List.of(spec("browser_click"), spec("browser_navigate"), spec("browser_tabs"), spec("browser_type"));

    private static ToolSpec spec(String name) {
        return new ToolSpec(name, name, new Json.Obj(Map.of()));
    }

    private static SessionContext context(List<String> active) {
        return new SessionContext("s", ALL, null, List.of(), active, active, List.of()).withUnlocks(List.of(RULE));
    }

    @Test
    void anEntryCallAddsWhatIsNotOnYetInTheOrderOfTheWholeSet() {
        assertEquals(List.of("browser_click", "browser_type"), ToolUnlocks.after(context(List.of("browser_navigate", "browser_tabs")),
                List.of("browser_tabs")));
        assertEquals(List.of("browser_type"), ToolUnlocks.after(context(List.of("browser_click", "browser_navigate")),
                List.of("browser_navigate")));
        assertEquals(List.of(), ToolUnlocks.after(context(List.of("browser_navigate")), List.of("grep")));
    }

    @Test
    void oldSessionsWithEverythingOnAndNoContextAddNothing() {
        assertEquals(List.of(), ToolUnlocks.after(context(null), List.of("browser_navigate")));
        assertEquals(List.of(), ToolUnlocks.after(null, List.of("browser_navigate")));
    }

    /** 压缩后重新冻结：开着过（toolsSeen）又在这次规则里的留着；规则里没有了（用户关掉）的不留。 */
    @Test
    void afterACompactionTheToolsThatWereAddedStay() {
        assertEquals(Set.of("browser_type", "browser_click"),
                ToolUnlocks.kept(List.of(RULE), List.of("grep", "browser_navigate", "browser_type", "browser_click")));
        assertEquals(Set.of("browser_click"), ToolUnlocks.kept(List.of(new ToolUnlock(RULE.after(), List.of("browser_click"))),
                List.of("browser_type", "browser_click")));
        assertEquals(Set.of(), ToolUnlocks.kept(null, List.of("browser_type")));
    }
}
