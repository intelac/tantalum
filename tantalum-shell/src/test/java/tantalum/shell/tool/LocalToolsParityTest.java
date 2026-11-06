package tantalum.shell.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.local.tool.LocalTool;
import tantalum.local.tool.Tools;
import tantalum.shell.plugin.Tool;
import tantalum.value.json.Json;

/**
 * 搬到电脑上的文件工具和服务器上原来的同名、同参数（local-workspace L5）：参数名、类型、哪些必填、能否并行都一样，旧会话里的调用
 * 原样交给新工具也对得上。说明文字按设计改过，不比。任务 13 删掉旧工具时，这个测试跟着删。
 */
class LocalToolsParityTest {

    @Test
    void movedFileToolsKeepTheirNamesAndParameters() {
        Observations observations = new Observations();
        List<Tool> old = List.of(new ReadFileTool(), new GrepTool(), new ListDirTool(), new FindFilesTool(),
                new WriteFileTool(observations), new EditFileTool(observations));
        List<LocalTool> moved = Tools.all().subList(0, old.size());
        Json none = new Json.Obj(Map.of());
        for (int i = 0; i < old.size(); i++) {
            assertEquals(old.get(i).name(), moved.get(i).name());
            assertEquals(shape(old.get(i).schema()), shape(moved.get(i).schema()), old.get(i).name());
            assertEquals(old.get(i).isConcurrencySafe(none), moved.get(i).isConcurrencySafe(none), old.get(i).name());
        }
    }

    /** schema 去掉说明文字，只留类型、参数名和类型、必填。 */
    private static Map<String, Object> shape(Json schema) {
        Map<String, Json> fields = ((Json.Obj) schema).fields();
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("type", fields.get("type"));
        Map<String, Json> types = new LinkedHashMap<>();
        ((Json.Obj) fields.get("properties")).fields().forEach((name, property) ->
                types.put(name, ((Json.Obj) property).fields().get("type")));
        shape.put("properties", types);
        shape.put("required", fields.get("required"));
        return shape;
    }
}
