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

    private Optional<ChoiceCheck.Problem> problem(String route, String model, String effort) {
        return check.problem(new ModelChoice(route, model, effort), false, false);
    }

    /** 种类和参数：控制台按这两样说给人听（console-i18n I9），诊断是英文。 */
    private static Optional<String> shown(Optional<ChoiceCheck.Problem> problem) {
        problem.ifPresent(found -> assertFalse(found.diagnostic().matches(".*[\\u3400-\\u9fff].*"), found.diagnostic()));
        return problem.map(found -> found.kind() + " " + new java.util.TreeMap<>(found.params()));
    }

    @Test
    void eachRowOfTheErrorTable() {
        String[][] rows = {
            {"local-2", "qwen", null, "ROUTE_UNAVAILABLE {route=local-2}"},
            {"down", "qwen", null, "ROUTE_UNAVAILABLE {route=down}"},
            {"claude", "claude-opus-4-5", "high", "MODEL_NOT_OFFERED {model=claude-opus-4-5}"},
            {"claude", "claude-sonnet-4-6", "xhigh", "LEVEL_UNKNOWN {level=xhigh, levels=[low, medium, high, max], model=Claude Sonnet 4.6}"},
            {"claude", "claude-sonnet-4-6", null, "LEVEL_REQUIRED {levels=[low, medium, high, max], model=Claude Sonnet 4.6}"},
            {"local", "qwen", "on", "NO_LEVELS {model=Name of qwen}"},
        };
        for (String[] row : rows) {
            assertEquals(Optional.of(row[3]), shown(problem(row[0], row[1], row[2])), String.join(" ", java.util.Arrays.asList(row).subList(0, 3)));
        }
        assertEquals(Optional.empty(), problem("claude", "claude-sonnet-4-6", "max"));
        assertEquals(Optional.empty(), problem("local", "qwen", null));
    }

    @Test
    void toolChangesAndImagesDependOnTheModel() {
        ModelChoice sonnet46 = new ModelChoice("claude", "claude-sonnet-4-6", "medium");
        assertEquals(Optional.of("TOOL_CHANGES {model=Claude Sonnet 4.6}"), shown(check.problem(sonnet46, true, false)));
        assertEquals(Optional.empty(), check.problem(sonnet46, false, false), "a session without tool changes can pick it");
        assertEquals(Optional.empty(), check.problem(new ModelChoice("claude", "claude-sonnet-5-5", "high"), true, true));
        assertEquals(Optional.of("NO_IMAGES {}"), shown(check.problem(new ModelChoice("local", "qwen", null), false, true)));
        assertTrue(check.usable(sonnet46));
        assertFalse(check.usable(new ModelChoice("down", "qwen", null)));
    }
}
