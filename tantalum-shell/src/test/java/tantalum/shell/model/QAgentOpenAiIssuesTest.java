package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;

/**
 * QAgent 问题清单第 1 条的同类（2026-11-14 对照时读代码发现）：模型调用不带参数的工具时流里的 arguments 是空串，
 * 下一轮请求原样发回 {@code "arguments": ""}；不少 OpenAI 兼容服务（vLLM、llama.cpp 的模板）要合法 JSON，整轮失败。
 */
class QAgentOpenAiIssuesTest {

    @Test
    void issue1_aCallWithoutArgumentsIsSentBackAsAnEmptyObject() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            Prompt prompt = new Prompt("sys", List.of(), List.of(Message.user("branches?"),
                    new Message(Role.ASSISTANT, List.of(new ToolCallBlock("c1", "git_branches", "", new Json.Obj(Map.of()))), false),
                    new Message(Role.USER, List.of(new ToolResultBlock("c1", "* main", false, null)), false)));
            OpenAiClientFixture.of(server.url(), "qwen", null, 4096, Duration.ofSeconds(5), Duration.ofSeconds(5), OverflowRules.defaults())
                    .call(prompt, new ChunkListener() {
                        @Override public void onText(String text) {}
                        @Override public void onReasoning(String text) {}
                    });
            var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(server.requests.getLast());
            String arguments = body.get("messages").get(2).get("tool_calls").get(0).get("function").get("arguments").asString();
            assertEquals("{}", arguments);
        }
    }
}
