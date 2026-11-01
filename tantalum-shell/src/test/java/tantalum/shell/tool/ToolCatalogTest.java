package tantalum.shell.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tantalum.kernel.tool.ToolSafety;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

class ToolCatalogTest {

    private static Tool tool(String name, String server, int limit, boolean safe) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "d"; }
            @Override public Json schema() { return new Json.Null(); }
            @Override public String server() { return server; }
            @Override public int serverConcurrency() { return limit; }
            @Override public boolean isConcurrencySafe(Json arguments) {
                if (name.equals("throwing")) {
                    throw new IllegalStateException();
                }
                return safe;
            }
            @Override public ToolOutput run(tantalum.shell.plugin.ToolContext context, Json arguments) { return ToolOutput.ok(""); }
        };
    }

    @Test
    void undeclaredLimitDefaultsToOne() {
        Tool plain = new Tool() {
            @Override public String name() { return "plain"; }
            @Override public String description() { return "d"; }
            @Override public Json schema() { return new Json.Null(); }
            @Override public ToolOutput run(tantalum.shell.plugin.ToolContext context, Json arguments) { return ToolOutput.ok(""); }
        };
        assertEquals(1, plain.serverConcurrency());
        assertFalse(plain.isConcurrencySafe(new Json.Null()));
    }

    @Test
    void listsSpecsInRegistrationOrder() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("b", "s", 1, false));
        catalog.register(tool("a", "s", 1, false));
        assertEquals(java.util.List.of("a", "b"), catalog.specs().stream().map(spec -> spec.name()).toList());
        assertTrue(catalog.find("a").isPresent());
        assertFalse(catalog.find("nope").isPresent());
    }

    @Test
    void duplicateNameFails() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("a", "s", 1, false));
        assertThrows(IllegalStateException.class, () -> catalog.register(tool("a", "s", 1, false)));
    }

    @Test
    void toolsOnOneServerShareALimiter() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("a", "s", 2, false));
        catalog.register(tool("b", "s", 2, false));
        assertTrue(catalog.limiterFor(catalog.find("a").orElseThrow()) == catalog.limiterFor(catalog.find("b").orElseThrow()));
    }

    @Test
    void conflictingLimitsForOneServerFail() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("a", "s", 2, false));
        assertThrows(IllegalStateException.class, () -> catalog.register(tool("b", "s", 3, false)));
    }

    @Test
    void safetyFollowsTheToolAndFailsClosed() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("safe", "s", 1, true));
        catalog.register(tool("unsafe", "s", 1, false));
        catalog.register(tool("throwing", "s", 1, true));
        ToolSafety safety = catalog.safety();
        assertTrue(safety.isConcurrencySafe("safe", new Json.Null()));
        assertFalse(safety.isConcurrencySafe("unsafe", new Json.Null()));
        assertFalse(safety.isConcurrencySafe("unknown", new Json.Null()));
        assertThrows(IllegalStateException.class, () -> safety.isConcurrencySafe("throwing", new Json.Null()));
    }
}
