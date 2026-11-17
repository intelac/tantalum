package tantalum.value.state;

import static tantalum.value.state.CompactionSettings.*;
import tantalum.value.state.CompactionSettings.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class CompactionSettingsFixture {

    private CompactionSettingsFixture() {}

    public static CompactionSettings of(long window, long reserve, long keepRecent, int maxKeptResultBytes, int maxSummarizedResultBytes, long summaryMaxTokens) {
        return new CompactionSettings(window, reserve, keepRecent, maxKeptResultBytes, maxSummarizedResultBytes, summaryMaxTokens, 0, 0);
    }
}
