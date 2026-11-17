package tantalum.shell.model;

import java.time.Duration;
import static tantalum.shell.model.OpenAiClient.*;
import tantalum.shell.model.OpenAiClient.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class OpenAiClientFixture {

    private OpenAiClientFixture() {}

    public static OpenAiClient of(String baseUrl, String model, String apiKey, int maxTokens, Duration firstChunk, Duration betweenChunks, OverflowRules overflow) {
        return new OpenAiClient(baseUrl, model, apiKey, maxTokens, firstChunk, betweenChunks, overflow, false);
    }
}
