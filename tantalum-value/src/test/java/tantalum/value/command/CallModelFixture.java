package tantalum.value.command;

import java.util.List;
import static tantalum.value.command.CallModel.*;
import tantalum.value.command.CallModel.*;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Prompt;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class CallModelFixture {

    private CallModelFixture() {}

    public static CallModel of(CommandId id, RunRef run, Prompt prompt) {
        return new CallModel(id, run, prompt, List.of());
    }
}
