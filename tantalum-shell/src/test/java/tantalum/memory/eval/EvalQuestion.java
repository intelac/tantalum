package tantalum.memory.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** {@code memory-eval/questions.json} 里的一道题（memory-final-plan 第 14 节）。 */
record EvalQuestion(String id, String category, String title, String phase, boolean expectedFail, JsonNode tier1, JsonNode tier2) {

    String check() {
        return tier1.path("check").asString();
    }

    static List<EvalQuestion> load() {
        try (InputStream in = EvalQuestion.class.getResourceAsStream("/memory-eval/questions.json")) {
            List<EvalQuestion> questions = new ArrayList<>();
            for (JsonNode question : EvalSeed.JSON.readTree(in).get("questions")) {
                String status = question.get("status").asString();
                if (!status.equals("pass") && !status.equals("expected-fail")) {
                    throw new IllegalStateException(question.get("id") + ": status must be pass or expected-fail");
                }
                questions.add(new EvalQuestion(question.get("id").asString(), question.get("category").asString(),
                        question.get("title").asString(), question.get("phase").asString(), status.equals("expected-fail"),
                        question.get("tier1"), question.get("tier2")));
            }
            return questions;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
