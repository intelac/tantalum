package tantalum.value.command;

import static tantalum.value.command.AssembleContext.*;
import tantalum.value.command.AssembleContext.*;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class AssembleContextFixture {

    private AssembleContextFixture() {}

    public static AssembleContext of(CommandId id, RunRef run) {
        return new AssembleContext(id, run, null, null);
    }
}
