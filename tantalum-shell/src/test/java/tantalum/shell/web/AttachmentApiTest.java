package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 任务 6：上传和读取接口，响应头和各种错误码（设计第 2 节、“Error Handling”）。 */
class AttachmentApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ModelClient IDLE = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);

    @TempDir
    Path temp;

    private TestServer server;

    @BeforeEach
    void start() {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), IDLE,
                "--tantalum.attachments.max-file-bytes=1000", "--tantalum.attachments.max-image-bytes=100000");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> upload(String user, String session, String name, byte[] body, String contentType) throws Exception {
        String query = name == null ? "" : "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        HttpRequest.Builder request = server.request(user, "/api/sessions/" + session + "/attachments" + query)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("content-type", contentType);
        }
        return server.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String user, String name, byte[] body) throws Exception {
        return upload(user, "s-1", name, body, "application/octet-stream");
    }

    private HttpResponse<byte[]> read(String user, String pathAndQuery) throws Exception {
        return server.http.send(server.request(user, pathAndQuery).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] sample(String name) throws Exception {
        try (InputStream in = AttachmentApiTest.class.getResourceAsStream("/attachments/" + name)) {
            return in.readAllBytes();
        }
    }

    /** 错误的代码和参数（console-i18n I9）：{@code attachment/too-large {"limit":1000}}；诊断是英文，不在这里比。 */
    private static String code(HttpResponse<String> response) throws Exception {
        JsonNode body = JSON.readTree(response.body());
        return body.path("code").asString() + (body.has("params") ? " " + body.get("params") : "");
    }

    @Test
    void uploadedFileReadsBackWithTheSameBytesAsADownload() throws Exception {
        byte[] content = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> uploaded = upload("alice", "季度 报告.html", content);
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        JsonNode ref = JSON.readTree(uploaded.body());
        assertEquals("file", ref.properties().iterator().next().getValue().asString(), "kind comes first");
        assertEquals("季度 报告.html", ref.get("name").asString());
        assertEquals(content.length, ref.get("bytes").asLong());
        assertFalse(ref.has("pages"));

        String id = ref.get("id").asString();
        HttpResponse<byte[]> read = read("alice", "/api/attachments/" + id + "?name=" + URLEncoder.encode("季度 报告.html", StandardCharsets.UTF_8));
        assertEquals(200, read.statusCode());
        assertArrayEquals(content, read.body());
        assertEquals("application/octet-stream", read.headers().firstValue("content-type").orElseThrow());
        assertEquals("attachment; filename*=UTF-8''%E5%AD%A3%E5%BA%A6%20%E6%8A%A5%E5%91%8A.html",
                read.headers().firstValue("content-disposition").orElseThrow());
        assertEquals("nosniff", read.headers().firstValue("x-content-type-options").orElseThrow());
        assertEquals("default-src 'none'; sandbox", read.headers().firstValue("content-security-policy").orElseThrow());
        assertEquals("private, max-age=31536000, immutable", read.headers().firstValue("cache-control").orElseThrow());
    }

    @Test
    void imagesAreShownInlineUnlessDownloadIsAsked() throws Exception {
        HttpResponse<String> uploaded = upload("alice", "IMG.JPG", sample("rotated.jpg"));
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        JsonNode ref = JSON.readTree(uploaded.body());
        assertEquals("image", ref.get("kind").asString());
        assertEquals(8, ref.get("width").asInt());
        assertEquals(16, ref.get("height").asInt());
        String id = ref.get("id").asString();

        HttpResponse<byte[]> inline = read("alice", "/api/attachments/" + id + "?name=IMG.JPG");
        assertEquals("image/jpeg", inline.headers().firstValue("content-type").orElseThrow());
        assertEquals("inline; filename*=UTF-8''IMG.JPG", inline.headers().firstValue("content-disposition").orElseThrow());
        assertEquals(ref.get("bytes").asLong(), inline.body().length);

        HttpResponse<byte[]> download = read("alice", "/api/attachments/" + id + "?name=IMG.JPG&download=1");
        assertEquals("attachment; filename*=UTF-8''IMG.JPG", download.headers().firstValue("content-disposition").orElseThrow());
    }

    @Test
    void anotherUsersAttachmentIsNotFound() throws Exception {
        String id = JSON.readTree(upload("alice", "a.txt", "mine".getBytes(StandardCharsets.UTF_8)).body()).get("id").asString();
        assertEquals(404, read("bob", "/api/attachments/" + id).statusCode());
        assertEquals(200, read("alice", "/api/attachments/" + id).statusCode());
        assertEquals(404, read("alice", "/api/attachments/sha256:" + "0".repeat(64)).statusCode());
        assertEquals(404, read("alice", "/api/attachments/not-an-id").statusCode());
        assertEquals(401, read(null, "/api/attachments/" + id).statusCode());
    }

    @Test
    void uploadsIntoSomeoneElsesSessionAreRefused() throws Exception {
        assertEquals(200, upload("alice", "alices-session", "a.txt", "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream").statusCode());
        HttpResponse<String> intruder = upload("bob", "alices-session", "a.txt", "x".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        assertEquals(404, intruder.statusCode());
    }

    @Test
    void errorsHaveTheirStatusAndReason() throws Exception {
        byte[] small = "x".getBytes(StandardCharsets.UTF_8);
        assertEquals(415, upload("alice", "s-1", "a.txt", small, "multipart/form-data; boundary=x").statusCode());
        assertEquals(415, upload("alice", "s-1", "a.txt", small, null).statusCode());
        assertEquals(400, upload("alice", "x".repeat(129), "a.txt", small, "application/octet-stream").statusCode());

        HttpResponse<String> tooLarge = upload("alice", "big.txt", new byte[1001]);
        assertEquals(413, tooLarge.statusCode());
        assertEquals("attachment/too-large {\"limit\":1000}", code(tooLarge));

        HttpResponse<String> notImage = upload("alice", "cat.png", small);
        assertEquals(422, notImage.statusCode());
        assertEquals("attachment/invalid-image", code(notImage));

        byte[] jpeg = sample("clean.jpg");
        HttpResponse<String> broken = upload("alice", "half.jpg", Arrays.copyOf(jpeg, jpeg.length / 2));
        assertEquals(422, broken.statusCode());
    }

    @Test
    void quotaFullIs413() throws Exception {
        server.close();
        server = new TestServer(temp.resolve("data2"), temp.resolve("ws2"), IDLE, "--tantalum.attachments.quota-bytes=8");
        assertEquals(200, upload("alice", "a.txt", "12345".getBytes(StandardCharsets.UTF_8)).statusCode());
        HttpResponse<String> full = upload("alice", "b.txt", "abcde".getBytes(StandardCharsets.UTF_8));
        assertEquals(413, full.statusCode());
        assertEquals("attachment/quota-full", code(full));
    }
}
