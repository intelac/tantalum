package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.json.JsonMapper;

/**
 * 两个人、两台电脑、同一个代理（2026-11-20 Yi 问：一个人能不能让代理去动别人的电脑）。从接口到电脑走一遍：
 * 每个人的对话只到自己的电脑；点名要别人电脑上的文件也只在自己的电脑上找；往别人的会话里说话进不去。
 * 模型是脚本：照用户那句话里的路径读、写，所以“模型被说动了”这种情况也在里面。
 */
class TwoComputersApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private Path alice;
    private Path bob;
    private TestServer server;
    private tantalum.shell.local.TestComputer bobsComputer;

    /** 用户说 {@code read <路径>} 就读，说 {@code write <路径>} 就写一个字；拿到工具结果后原样说出来。 */
    private final ModelClient model = (prompt, chunks) -> {
        ContentBlock last = prompt.messages().getLast().content().getLast();
        if (last instanceof ToolResultBlock result) {
            return new ModelOutcome.Replied(List.of(new TextBlock("Result: " + result.text())), StopReason.END_TURN, "m", null);
        }
        String said = "";
        for (tantalum.value.model.Message message : prompt.messages()) {
            for (ContentBlock block : message.content()) {
                if (block instanceof TextBlock text && (text.text().startsWith("read ") || text.text().startsWith("write "))) {
                    said = text.text().strip();
                }
            }
        }
        String path = said.substring(said.indexOf(' ') + 1);
        Map<String, Json> arguments = said.startsWith("write ")
                ? Map.of("path", new Json.Str(path), "content", new Json.Str("x")) : Map.of("path", new Json.Str(path));
        String name = said.startsWith("write ") ? "write_file" : "read_file";
        return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", name, JSON.writeValueAsString(
                said.startsWith("write ") ? Map.of("path", path, "content", "x") : Map.of("path", path)), new Json.Obj(arguments))),
                StopReason.TOOL_USE, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        alice = Files.createDirectory(temp.resolve("alice-mac")).toRealPath();
        bob = Files.createDirectory(temp.resolve("bob-pc")).toRealPath();
        Files.writeString(alice.resolve("a.txt"), "alice's file");
        Files.writeString(bob.resolve("a.txt"), "bob's file");
        Files.writeString(alice.resolve("secret.txt"), "only on alice's computer");
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
        server.connectComputer("alice", alice, true);
        bobsComputer = server.connectComputer("bob", bob, true);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String say(String user, String session, String text) throws Exception {
        String request = session + "-" + Integer.toHexString(text.hashCode());
        int status = server.post(user, "/api/sessions/" + session + "/submissions", "{\"requestId\":\"" + request
                + "\",\"mode\":\"prompt\",\"text\":" + JSON.writeValueAsString(text) + "}").statusCode();
        if (status != 200) {
            return "status " + status;
        }
        String body = "";
        long runs = 0;
        for (int i = 0; i < 1000 && runs < 1; i++) {
            Thread.sleep(10);
            body = server.get(user, "/api/sessions/" + session + "/messages").body();
            runs = body.contains(JSON.writeValueAsString(text)) && body.lastIndexOf("\"endReason\"") > body.lastIndexOf(JSON.writeValueAsString(text)) ? 1 : 0;
        }
        return body;
    }

    @Test
    void eachPersonsConversationOnlyReachesTheirOwnComputer() throws Exception {
        // 同一句话，各读各的
        assertTrue(say("alice", "sa", "read a.txt").contains("alice's file"));
        String bobs = say("bob", "sb", "read a.txt");
        assertTrue(bobs.contains("bob's file") && !bobs.contains("alice's file"), bobs);

        // Bob 点名要 Alice 电脑上的文件（相对路径、绝对路径）：只在 Bob 自己的电脑上找，找不到
        String relative = say("bob", "sb", "read secret.txt");
        assertFalse(relative.contains("only on alice's computer"), relative);
        String absolute = say("bob", "sb", "read " + alice.resolve("secret.txt"));
        assertFalse(absolute.contains("only on alice's computer"), absolute);
        assertTrue(absolute.contains("allowed directories"), absolute);

        // Bob 让代理往 Alice 的目录里写：写不进去；写相对路径落在 Bob 自己的电脑上
        String write = say("bob", "sb", "write " + alice.resolve("planted.txt"));
        assertFalse(Files.exists(alice.resolve("planted.txt")), write);
        say("bob", "sb", "write note.txt");
        assertTrue(Files.exists(bob.resolve("note.txt")));
        assertFalse(Files.exists(alice.resolve("note.txt")));

        // Bob 往 Alice 的会话里说话：进不去，Alice 的会话里也没有多出东西
        String before = server.get("alice", "/api/sessions/sa/messages").body();
        assertEquals("status 404", say("bob", "sa", "read secret.txt"));
        assertEquals(404, server.get("bob", "/api/sessions/sa/messages").statusCode());
        assertEquals(before, server.get("alice", "/api/sessions/sa/messages").body());

        // Bob 的电脑断开以后，他的对话不会改走 Alice 的电脑
        bobsComputer.close();
        String offline = say("bob", "sb2", "read a.txt");
        assertFalse(offline.contains("alice's file"), offline);
    }
}
