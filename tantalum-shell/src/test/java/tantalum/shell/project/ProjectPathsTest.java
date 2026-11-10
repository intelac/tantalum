package tantalum.shell.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** workspaces 任务 5（W2、W4）：用和控制台共用的用例表测绝对路径、键和默认名字。 */
class ProjectPathsTest {

    @TestFactory
    List<DynamicTest> everySharedCase() throws IOException {
        JsonNode table;
        try (InputStream in = getClass().getResourceAsStream("/project/paths.json")) {
            table = JsonMapper.builder().build().readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode item : table.get("cases")) {
            String path = item.get("path").asString();
            tests.add(DynamicTest.dynamicTest("[" + path + "]", () -> {
                boolean absolute = item.get("absolute").asBoolean();
                assertEquals(absolute, ProjectPaths.isAbsolute(path), "absolute");
                if (absolute) {
                    assertEquals(item.get("key").asString(), ProjectPaths.key(path), "key");
                    assertEquals(item.get("name").asString(), ProjectPaths.name(path), "name");
                }
            }));
        }
        for (JsonNode item : table.get("within")) {
            String dir = item.get("dir").asString();
            String root = item.get("root").asString();
            tests.add(DynamicTest.dynamicTest("[" + dir + "] in [" + root + "]",
                    () -> assertEquals(item.get("within").asBoolean(), ProjectPaths.within(dir, root))));
        }
        return tests;
    }
}
