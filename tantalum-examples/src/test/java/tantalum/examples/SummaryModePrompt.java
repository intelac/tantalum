package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;

/** 两个真实模型共用的摘要模式请求：形状和内核将来发出的摘要调用相同。 */
final class SummaryModePrompt {

    private SummaryModePrompt() {}

    static Prompt build() {
        ToolSpec read = new ToolSpec("read_file", "Read a file.", new Json.Obj(Map.of("type", new Json.Str("object"),
                "properties", new Json.Obj(Map.of("path", new Json.Obj(Map.of("type", new Json.Str("string"))))))));
        List<Message> history = List.of(
                Message.user("Read config.yaml and tell me the port. Never change the file."),
                new Message(Role.ASSISTANT, List.of(new TextBlock("Reading it."),
                        new ToolCallBlock("c1", "read_file", "{\"path\":\"config.yaml\"}",
                                new Json.Obj(Map.of("path", new Json.Str("config.yaml"))))), false),
                new Message(Role.USER, List.of(new ToolResultBlock("c1", "server:\n  port: 8443\n  host: 0.0.0.0\n", false, null)), false),
                new Message(Role.ASSISTANT, List.of(new TextBlock("The port is 8443.")), false),
                Message.user("Summarize the conversation so far in at most five lines: goal, constraints, progress, next steps. "
                        + "Keep file names and numbers exactly. Do not call any tool."));
        return new Prompt("You are a helpful agent.", List.of(read), history, new Prompt.Output(false, 512));
    }

    static String text(ModelOutcome.Replied replied) {
        return replied.content().stream().filter(TextBlock.class::isInstance).map(block -> ((TextBlock) block).text())
                .reduce("", String::concat);
    }

    static void check(ModelOutcome.Replied replied) {
        assertEquals(StopReason.END_TURN, replied.stop(), "summary must end normally");
        assertFalse(replied.content().stream().anyMatch(ToolCallBlock.class::isInstance), "tools were disabled");
        String text = text(replied);
        assertTrue(text.contains("8443") && text.contains("config.yaml"), text);
    }
}
