package tantalum.value.model;

import java.util.List;
import static tantalum.value.model.Prompt.*;
import tantalum.value.model.Prompt.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class PromptFixture {

    private PromptFixture() {}

    public static Prompt of(String system, List<ToolSpec> tools, List<Message> messages, Output output) {
        return new Prompt(system, tools, messages, output, null);
    }
}
