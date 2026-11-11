package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.Workspace;
import tantalum.value.hook.Params;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/** 端到端测试靠这个工具的回答判断密码到没到工具手里，所以它自己先要对：说长度，不回显。 */
class E2eSecretToolTest {

    @Test
    void itReportsTheLengthAndNeverTheToken(@TempDir Path dir) throws Exception {
        E2eSecretTool tool = new E2eSecretTool();
        assertEquals(Param.Type.SECRET, tool.params().get(0).type());
        ToolContext none = new ToolContext(new Principal("u"), new SessionId("s"), new Workspace(dir));
        assertEquals("没设置", tool.run(none, new Json.Null()).text());
        ToolContext set = none.with(Params.NONE, key -> key.equals(E2eSecretTool.KEY) ? Optional.of("hunter2-e2e") : Optional.empty());
        String said = tool.run(set, new Json.Null()).text();
        assertEquals("收到了，长度 11", said);
        assertFalse(said.contains("hunter2"));
    }
}
