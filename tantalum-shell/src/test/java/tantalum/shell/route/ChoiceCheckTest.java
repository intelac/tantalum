package tantalum.shell.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tantalum.shell.model.OpenAiThinking;
import tantalum.value.model.ModelChoice;

/** model-picker 任务 6（需求 5.6、5.7、3.5）：设计 Error Handling 表的前六行，消息照原文。 */
class ChoiceCheckTest {

    private static final Levels SONNET46 = new Levels(List.of("low", "medium", "high", "max"), "medium");

    private Routes routes;
    private ChoiceCheck check;

    @BeforeEach
    void start() {
        Map<String, ModelList> lists = Map.of(
                "claude", () -> List.of(
                        new ModelInfo("claude", "claude-sonnet-4-6", "Claude Sonnet 4.6", 1_000_000, ModelInfo.Source.MODEL, 64_000, true, SONNET46, false),
                        new ModelInfo("claude", "claude-sonnet-5-5", "Claude Sonnet 5.5", 1_000_000, ModelInfo.Source.MODEL, 64_000, true,
                                new Levels(Levels.CLAUDE, "medium"), true)),
                "local", () -> List.of(RouteFixtures.model("local", "qwen", Levels.NONE, false, true)),
                "down", () -> {
                    throw new ModelList.Unreadable("连不上");
                });
        routes = Routes.create(List.of(RouteFixtures.claude(List.of()), RouteFixtures.openAi("local", null, OpenAiThinking.NONE, 0),
                RouteFixtures.openAi("down", null, OpenAiThinking.NONE, 0)), "claude",
                (route, key) -> new Routes.Built((prompt, chunks) -> null, lists.get(route.name())), null, name -> "key", true, Clock.systemUTC())
                .start(Duration.ofSeconds(2));
        check = new ChoiceCheck(routes);
    }

    @AfterEach
    void stop() {
        routes.close();
    }

    private Optional<String> problem(String route, String model, String effort) {
        return check.problem(new ModelChoice(route, model, effort), false, false);
    }

    @Test
    void eachRowOfTheErrorTable() {
        String[][] rows = {
            {"local-2", "qwen", null, "模型路线 local-2 现在不可用，请换一个模型"},
            {"down", "qwen", null, "模型路线 down 现在不可用，请换一个模型"},
            {"claude", "claude-opus-4-5", "high", "模型 claude-opus-4-5 现在不提供，请换一个"},
            {"claude", "claude-sonnet-4-6", "xhigh", "Claude Sonnet 4.6 没有“很高”这一档，可选：低、中、高、最高"},
            {"claude", "claude-sonnet-4-6", null, "Claude Sonnet 4.6 要选一个思考档位，可选：低、中、高、最高"},
            {"local", "qwen", "on", "Name of qwen 没有思考档位"},
        };
        for (String[] row : rows) {
            assertEquals(Optional.of(row[3]), problem(row[0], row[1], row[2]), String.join(" ", java.util.Arrays.asList(row).subList(0, 3)));
        }
        assertEquals(Optional.empty(), problem("claude", "claude-sonnet-4-6", "max"));
        assertEquals(Optional.empty(), problem("local", "qwen", null));
    }

    @Test
    void toolChangesAndImagesDependOnTheModel() {
        ModelChoice sonnet46 = new ModelChoice("claude", "claude-sonnet-4-6", "medium");
        assertEquals(Optional.of("这个会话中途改过工具，Claude Sonnet 4.6 不支持，请换一个模型"), check.problem(sonnet46, true, false));
        assertEquals(Optional.empty(), check.problem(sonnet46, false, false), "a session without tool changes can pick it");
        assertEquals(Optional.empty(), check.problem(new ModelChoice("claude", "claude-sonnet-5-5", "high"), true, true));
        assertEquals(Optional.of("当前选的模型不支持图片"), check.problem(new ModelChoice("local", "qwen", null), false, true));
        assertTrue(check.usable(sonnet46));
        assertFalse(check.usable(new ModelChoice("down", "qwen", null)));
    }
}
