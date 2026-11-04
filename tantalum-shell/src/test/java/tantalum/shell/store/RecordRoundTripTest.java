package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.RecordCodec;
import tantalum.shell.journal.RecordMemo;
import tantalum.value.command.CallModel;
import tantalum.value.decision.StepRecord;
import tantalum.value.id.SessionId;
import tools.jackson.databind.JsonNode;

/**
 * record-results 任务 1–4：真实内核走一遍交错场景，每一步的结果行编码再解码得到同样的值，套回去得到和实时相同的状态；
 * 长字符串和请求只记一次。
 */
class RecordRoundTripTest {

    @TempDir
    Path dir;

    private ReplayScenario scenario;
    private final EventCodec events = EventCodec.withBuiltins();
    private final RecordCodec codec = new RecordCodec(events);

    @BeforeEach
    void start() throws Exception {
        scenario = new ReplayScenario(dir);
        scenario.run();
    }

    @AfterEach
    void stop() throws Exception {
        scenario.close();
    }

    @Test
    void everyStepSurvivesTheFileAndAppliesToTheSameState() {
        Map<SessionId, RecordMemo> writers = new HashMap<>();
        Map<SessionId, RecordMemo> readers = new HashMap<>();
        int calls = 0;
        for (IndexFixture.Stepped step : scenario.fixture.steps) {
            SessionId session = step.envelope().session();
            RecordMemo writer = writers.computeIfAbsent(session, ignored -> RecordMemo.writer());
            RecordMemo reader = readers.computeIfAbsent(session, ignored -> RecordMemo.reader());
            // 文件里事件行在前：两边都先收录它的长字符串
            writer.see(events.mapper().valueToTree(step.envelope().event()));
            reader.see(events.mapper().valueToTree(step.envelope().event()));

            StepRecord record = StepRecord.of(step.envelope(), step.before(), step.decision());
            String line = codec.encode(record, writer);
            RecordCodec.Decoded decoded = codec.decode(line, reader);

            assertEquals(record, decoded.record(), "seq " + record.seq());
            assertEquals(step.decision(), decoded.record().apply(step.envelope(), step.before()), "seq " + record.seq());
            assertTrue(decoded.fingerprintMismatches().isEmpty());
            calls += (int) record.commands().stream().filter(CallModel.class::isInstance).count();
        }
        assertTrue(calls > 5, "the scenario calls the model many times");
    }

    @Test
    void eachCallWritesOnlyWhatIsNewSinceTheLastOne() {
        Map<SessionId, RecordMemo> writers = new HashMap<>();
        int calls = 0;
        for (IndexFixture.Stepped step : scenario.fixture.steps) {
            RecordMemo writer = writers.computeIfAbsent(step.envelope().session(), ignored -> RecordMemo.writer());
            writer.see(events.mapper().valueToTree(step.envelope().event()));
            JsonNode line = events.mapper().readTree(codec.encode(StepRecord.of(step.envelope(), step.before(), step.decision()), writer));
            for (JsonNode command : line.path("commands")) {
                if (!command.path("type").asString().equals("CallModel")) {
                    continue;
                }
                JsonNode prompt = command.get("prompt");
                if (calls++ > 0 && prompt.get("keep").asInt() > 0) {
                    // 历史不随每次调用重写：新加的只有这一轮的一两条，而且刚追加进历史的写成下标
                    assertTrue(prompt.get("add").size() <= 3, prompt.toString());
                    prompt.get("add").forEach(item -> assertTrue(item.isInt() || !item.path("role").asString().equals("ASSISTANT"),
                            "a reply already in the history is written again: " + item));
                }
            }
        }
        assertTrue(calls > 5);
    }
}
