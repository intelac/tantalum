package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tantalum.shell.model.AttachmentMedia;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.model.OpenAiThinking;
import tantalum.shell.route.RouteClients;
import tantalum.shell.route.RouteSettings;
import tantalum.shell.route.Routes;
import tantalum.value.command.CallModel;
import tantalum.value.event.Event;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/** model-picker 任务 10（需求 5.1、5.8，M16）：按 {@code Prompt.model} 的路线调用，每条路线自己的并发上限。 */
class RoutedModelHandlerTest {

    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));
    private static final ChunkListener QUIET = new ChunkListener() {
        @Override public void onText(String text) { }
        @Override public void onReasoning(String text) { }
    };

    private Routes routes;

    @AfterEach
    void close() {
        if (routes != null) {
            routes.close();
        }
    }

    /** 回复里写上是谁回的。 */
    private static ModelClient named(String name) {
        return (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock(name)), StopReason.END_TURN,
                prompt.model() == null ? name : prompt.model().model(), null);
    }

    private static RouteSettings claude() {
        return new RouteSettings("claude", "Claude", RouteSettings.Kind.CLAUDE, "ANTHROPIC_API_KEY", "claude-sonnet-5-5", List.of(),
                64_000, 200_000, 1.5, 4, Duration.ofSeconds(5), Duration.ofSeconds(5), "medium", false, null, true, null, null, true, Map.of());
    }

    /** 本地路线并发 1，等名额最多 {@code wait}。 */
    private static RouteSettings local(Duration wait) {
        return new RouteSettings("local", "本地", RouteSettings.Kind.OPENAI, null, "qwen", List.of(), 16_384, 131_072, 1.75, 1, wait,
                Duration.ofSeconds(5), null, false, "http://127.0.0.1:1/v1", false, OpenAiThinking.CHAT_TEMPLATE, null, false, Map.of());
    }

    private ModelHandler handler(ModelClient claude, ModelClient local, Duration localWait) {
        routes = Routes.create(List.of(claude(), local(localWait)), "claude", (route, key) -> {
            throw new AssertionError("no real client");
        }, new RouteClients(Map.of("claude", claude, "local", local)), name -> null, true, Clock.systemUTC()).start(Duration.ofSeconds(2));
        return new ModelHandler(routes, run -> QUIET, Clock.systemUTC(), run -> AttachmentMedia.NONE);
    }

    private static CallModel call(String id, ModelChoice model) {
        return new CallModel(new CommandId(id), RUN, new Prompt("s", List.of(), List.of(Message.user("hi")), Prompt.Output.DEFAULT, model));
    }

    private static String repliedBy(Event event) {
        return ((TextBlock) assertInstanceOf(ModelReplied.class, event).content().getFirst()).text();
    }

    @Test
    void eachCallGoesToTheRouteItsPromptNames() {
        ModelHandler handler = handler(named("claude"), named("local"), Duration.ofSeconds(1));
        assertEquals("claude", repliedBy(handler.handle(call("c1", new ModelChoice("claude", "claude-opus-5-5", "high")))));
        assertEquals("local", repliedBy(handler.handle(call("c2", new ModelChoice("local", "qwen", "on")))));
        assertEquals("claude", repliedBy(handler.handle(call("c3", null))), "no choice (old records, tests): the default route");
        ModelFailed gone = assertInstanceOf(ModelFailed.class, handler.handle(call("c4", new ModelChoice("kimi", "k2", null))));
        assertEquals(FailureReason.INVALID_REQUEST, gone.reason());
        assertFalse(gone.retryable());
    }

    /** M16：本地路线并发 1 被占着时，第二个本地调用等不到名额，Claude 的调用照常进行。 */
    @Test
    void aBusyRouteDoesNotHoldUpTheOthers() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelClient slowLocal = (prompt, chunks) -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return new ModelOutcome.Replied(List.of(new TextBlock("local")), StopReason.END_TURN, "qwen", null);
        };
        ModelHandler handler = handler(named("claude"), slowLocal, Duration.ofMillis(200));
        ModelChoice qwen = new ModelChoice("local", "qwen", "on");
        CompletableFuture<Event> first = CompletableFuture.supplyAsync(() -> handler.handle(call("c1", qwen)));
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        try {
            ModelFailed waited = assertInstanceOf(ModelFailed.class, handler.handle(call("c2", qwen)));
            assertEquals(FailureReason.TIMEOUT, waited.reason(), "the local route's one slot is taken");
            assertEquals("claude", repliedBy(handler.handle(call("c3", new ModelChoice("claude", "claude-sonnet-5-5", "medium")))));
        } finally {
            release.countDown();
        }
        assertEquals("local", repliedBy(first.get(5, TimeUnit.SECONDS)));
    }
}
