package tantalum.shell.model;

import java.time.Duration;
import static tantalum.shell.model.ClaudeClient.*;
import tantalum.shell.model.ClaudeClient.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class ClaudeClientFixture {

    private ClaudeClientFixture() {}

    public static ClaudeClient.Settings settings(String defaultModel, long maxTokens, String defaultEffort, boolean fallbacks, Duration firstChunk, Duration betweenChunks, OverflowRules overflow, boolean strictThinking) {
        return new ClaudeClient.Settings(defaultModel, maxTokens, defaultEffort, fallbacks, firstChunk, betweenChunks, overflow, strictThinking, true);
    }
}
