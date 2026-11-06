package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;

/** 固定的工具清单（L5、L9）：名字沿用旧的、顺序不变、参数是模型能用的 JSON Schema、类别对应插件页的子开关。 */
class ToolsTest {

    private static final Set<String> SCHEMA_TYPES = Set.of("string", "integer", "number", "boolean", "array", "object");

    /** 前六个和服务器上原来的文件工具同名、同顺序，旧会话里的调用对得上；顺序固定，工具定义的缓存前缀才不变。 */
    @Test
    void namesAndOrderAreFixed() {
        assertEquals(List.of("read_file", "grep", "list_dir", "find_files", "write_file", "edit_file",
                        "git_log", "git_show", "git_diff", "git_blame", "git_branches", "bash"),
                Tools.all().stream().map(LocalTool::name).toList());
        assertEquals(Tools.all(), Tools.all());
    }

    @Test
    void kindsMatchThePluginSwitches() {
        Map<String, LocalTool.Kind> kinds = new java.util.HashMap<>();
        Tools.all().forEach(tool -> kinds.put(tool.name(), tool.kind()));
        for (String read : List.of("read_file", "grep", "list_dir", "find_files")) {
            assertEquals(LocalTool.Kind.READ, kinds.get(read), read);
        }
        assertEquals(LocalTool.Kind.WRITE, kinds.get("write_file"));
        assertEquals(LocalTool.Kind.WRITE, kinds.get("edit_file"));
        Tools.all().stream().filter(tool -> tool.name().startsWith("git_"))
                .forEach(tool -> assertEquals(LocalTool.Kind.GIT, tool.kind(), tool.name()));
        assertEquals(LocalTool.Kind.SHELL, kinds.get("bash"));
    }

    /**
     * 每个工具的参数都是对象类型的 JSON Schema：有 properties，每个参数有合法的类型，required 都在 properties 里。
     * 名字符合模型接口对工具名的要求（字母、数字、下划线、横线，最多 64 个）。说明里不再提服务器的工作目录和附件。
     */
    @Test
    void everySchemaIsAValidObjectSchema() {
        for (LocalTool tool : Tools.all()) {
            String name = tool.name();
            assertTrue(name.matches("[a-zA-Z0-9_-]{1,64}"), name);
            assertFalse(tool.description().isBlank(), name);
            assertFalse(tool.description().contains("workspace"), name);
            assertFalse(tool.description().contains("attachment"), name);
            Json.Obj schema = assertInstanceOf(Json.Obj.class, tool.schema(), name);
            assertEquals(new Json.Str("object"), schema.fields().get("type"), name);
            Json.Obj properties = assertInstanceOf(Json.Obj.class, schema.fields().get("properties"), name);
            assertFalse(properties.fields().isEmpty(), name);
            properties.fields().forEach((param, definition) -> {
                Json.Obj property = assertInstanceOf(Json.Obj.class, definition, name + "." + param);
                Json.Str type = assertInstanceOf(Json.Str.class, property.fields().get("type"), name + "." + param);
                assertTrue(SCHEMA_TYPES.contains(type.value()), name + "." + param);
            });
            Json.Arr required = assertInstanceOf(Json.Arr.class, schema.fields().get("required"), name);
            for (Json item : required.items()) {
                Json.Str param = assertInstanceOf(Json.Str.class, item, name);
                assertTrue(properties.fields().containsKey(param.value()), name + " requires unknown " + param.value());
            }
        }
    }

    @Test
    void namesAreUnique() {
        assertEquals(Tools.all().size(), Tools.all().stream().map(LocalTool::name).distinct().count());
    }
}
