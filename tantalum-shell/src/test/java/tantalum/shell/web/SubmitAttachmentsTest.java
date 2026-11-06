package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.Engine;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 任务 10：提交里的附件引用只认本人目录里的元数据；数量、合计大小和模型是否收图片在服务器再查一遍。 */
class SubmitAttachmentsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ModelClient IDLE = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);

    @TempDir
    Path temp;

    private TestServer server;

    @AfterEach
    void stop() {
        server.close();
    }

    private void start(String... extra) {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), IDLE, extra);
    }

    private JsonNode upload(String user, String name, byte[] body) throws Exception {
        HttpResponse<String> response = server.http.send(server.request(user, "/api/sessions/s-1/attachments?name=" + name)
                .header("content-type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static byte[] sample(String name) throws Exception {
        try (InputStream in = SubmitAttachmentsTest.class.getResourceAsStream("/attachments/" + name)) {
            return in.readAllBytes();
        }
    }

    private HttpResponse<String> submit(String user, String mode, String text, String attachments) throws Exception {
        String body = "{\"requestId\":\"" + System.nanoTime() + "\",\"mode\":\"" + mode + "\""
                + (text == null ? "" : ",\"text\":\"" + text + "\"")
                + (attachments == null ? "" : ",\"attachments\":" + attachments) + "}";
        return server.post(user, "/api/sessions/s-1/submissions", body);
    }

    /** 错误的代码和参数（console-i18n I9）：{@code attachment/too-large {"limit":1000}}；诊断是英文，不在这里比。 */
    private static String code(HttpResponse<String> response) throws Exception {
        JsonNode body = JSON.readTree(response.body());
        return body.path("code").asString() + (body.has("params") ? " " + body.get("params") : "");
    }

    /** 等提交进了会话历史，返回第一条用户消息。 */
    private Message firstUserMessage(String user) throws Exception {
        Engine engine = server.context.getBean(Engine.class);
        for (int i = 0; i < 200; i++) {
            Optional<Message> first = engine.queries().session(new Principal(user), new SessionId("s-1"))
                    .flatMap(view -> view.state().messages().stream().findFirst());
            if (first.isPresent()) {
                return first.get();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("submission never reached the session");
    }

    @Test
    void theEventCarriesTheServersMetadataNotTheClients() throws Exception {
        start();
        JsonNode image = upload("alice", "cat.jpg", sample("rotated.jpg"));
        JsonNode file = upload("alice", "notes.txt", "hello".getBytes(StandardCharsets.UTF_8));
        String id = image.get("id").asString();
        HttpResponse<String> response = submit("alice", "prompt", "look", "[{\"id\":\"" + id + "\",\"name\":\"shown.jpg\","
                + "\"bytes\":1,\"width\":9999,\"height\":1},{\"id\":\"" + file.get("id").asString() + "\",\"name\":\"n.txt\"}]");
        assertEquals(200, response.statusCode(), response.body());

        List<ContentBlock> content = firstUserMessage("alice").content();
        assertEquals(new TextBlock("look"), content.get(0));
        assertEquals(new AttachmentBlock(new ImageRef(id, "shown.jpg", "image/jpeg", image.get("bytes").asLong(), 8, 16)), content.get(1));
        assertEquals(new AttachmentBlock(new FileRef(file.get("id").asString(), "n.txt", "application/octet-stream", 5, null)), content.get(2));
    }

    @Test
    void attachmentsAloneAreEnough() throws Exception {
        start();
        String id = upload("alice", "notes.txt", "hello".getBytes(StandardCharsets.UTF_8)).get("id").asString();
        assertEquals(200, submit("alice", "prompt", null, "[{\"id\":\"" + id + "\",\"name\":\"notes.txt\"}]").statusCode());
        assertEquals(new TextBlock(""), firstUserMessage("alice").content().getFirst());
        assertEquals(400, submit("alice", "prompt", null, "[]").statusCode());
    }

    @Test
    void unknownOrSomeoneElsesAttachmentsAreRejected() throws Exception {
        start();
        String alices = upload("alice", "a.txt", "secret".getBytes(StandardCharsets.UTF_8)).get("id").asString();
        HttpResponse<String> foreign = server.post("bob", "/api/sessions/s-2/submissions",
                "{\"requestId\":\"r\",\"mode\":\"prompt\",\"text\":\"x\",\"attachments\":[{\"id\":\"" + alices + "\",\"name\":\"a.txt\"}]}");
        assertEquals(400, foreign.statusCode());
        assertEquals("attachment/not-found", code(foreign));
        assertEquals(400, submit("alice", "prompt", "x", "[{\"id\":\"sha256:" + "0".repeat(64) + "\"}]").statusCode());
        assertEquals(400, submit("alice", "prompt", "x", "[{\"id\":\"../etc/passwd\"}]").statusCode());
        assertEquals(400, submit("alice", "prompt", "x", "{\"id\":\"" + alices + "\"}").statusCode());
        assertEquals(400, submit("alice", "prompt", "x", "[\"" + alices + "\"]").statusCode());
    }

    @Test
    void perMessageLimitsAreCheckedAgainAndNameTheLimit() throws Exception {
        start("--tantalum.attachments.max-images-per-message=1", "--tantalum.attachments.max-files-per-message=1");
        String a = upload("alice", "a.jpg", sample("rotated.jpg")).get("id").asString();
        String b = upload("alice", "b.png", sample("clean.png")).get("id").asString();
        HttpResponse<String> images = submit("alice", "steer", "x", "[{\"id\":\"" + a + "\"},{\"id\":\"" + b + "\"}]");
        assertEquals(400, images.statusCode());
        assertEquals("attachment/too-many-images {\"count\":2,\"max\":1}", code(images));

        String f = upload("alice", "f.txt", "1".getBytes(StandardCharsets.UTF_8)).get("id").asString();
        String g = upload("alice", "g.txt", "2".getBytes(StandardCharsets.UTF_8)).get("id").asString();
        HttpResponse<String> files = submit("alice", "follow-up", "x", "[{\"id\":\"" + f + "\"},{\"id\":\"" + g + "\"}]");
        assertEquals(400, files.statusCode());
        assertEquals("attachment/too-many-files {\"count\":2,\"max\":1}", code(files));
    }

    @Test
    void totalImageBytesAreChecked() throws Exception {
        start("--tantalum.attachments.max-image-bytes-per-message=1000");
        String a = upload("alice", "a.jpg", sample("rotated.jpg")).get("id").asString();
        String b = upload("alice", "b.jpg", sample("gps.jpg")).get("id").asString();
        HttpResponse<String> response = submit("alice", "prompt", "x", "[{\"id\":\"" + a + "\"},{\"id\":\"" + b + "\"}]");
        assertEquals(400, response.statusCode());
        assertEquals("attachment/images-too-large {\"limit\":1000}", code(response));
    }

    /** OpenAI 兼容的模型默认不收图片（A7）：带图片的提交拒绝，只带文件的照常。 */
    @Test
    void imagesAreRefusedWhenTheModelTakesTextOnly() throws Exception {
        start("--tantalum.provider=local", "--tantalum.openai-routes.local.base-url=http://127.0.0.1:8090/v1", "--tantalum.openai-routes.local.model=qwen3.8-flash-next-unsloth-ud-q4_k_xl", "--tantalum.openai-routes.local.context-window=131072");
        String image = upload("alice", "a.jpg", sample("rotated.jpg")).get("id").asString();
        String file = upload("alice", "f.txt", "1".getBytes(StandardCharsets.UTF_8)).get("id").asString();
        HttpResponse<String> refused = submit("alice", "prompt", "x", "[{\"id\":\"" + image + "\"}]");
        assertEquals(400, refused.statusCode());
        assertEquals("attachment/model-no-images", code(refused), "model-picker M15");
        assertEquals(200, submit("alice", "prompt", "x", "[{\"id\":\"" + file + "\"}]").statusCode());
    }
}
