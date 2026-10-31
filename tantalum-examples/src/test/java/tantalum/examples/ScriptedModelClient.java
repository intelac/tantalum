package tantalum.examples;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/** 按脚本返回结果，并记下每次收到的 prompt。 */
final class ScriptedModelClient implements ModelClient {

    final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    private final Deque<ModelOutcome> script;

    ScriptedModelClient(List<ModelOutcome> script) {
        this.script = new ArrayDeque<>(script);
    }

    static ModelOutcome tools(ContentBlock... calls) {
        return new ModelOutcome.Replied(List.of(calls), StopReason.TOOL_USE, "scripted", null);
    }

    static ModelOutcome answer(String text) {
        return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, "scripted", null);
    }

    @Override
    public synchronized ModelOutcome call(Prompt prompt, ChunkListener chunks) {
        prompts.add(prompt);
        ModelOutcome next = script.poll();
        if (next == null) {
            throw new AssertionError("script ended, but the model was called again");
        }
        if (next instanceof ModelOutcome.Replied reply) {
            reply.content().stream().filter(TextBlock.class::isInstance)
                    .forEach(block -> chunks.onText(((TextBlock) block).text()));
        }
        return next;
    }
}
