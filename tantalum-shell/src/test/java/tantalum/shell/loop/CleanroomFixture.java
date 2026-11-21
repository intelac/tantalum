package tantalum.shell.loop;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import static tantalum.shell.loop.Cleanroom.*;
import tantalum.kernel.Kernel;
import tantalum.shell.loop.Cleanroom.*;
import tantalum.value.command.Command;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class CleanroomFixture {

    private CleanroomFixture() {}

    public static Cleanroom of(Kernel kernel, BooleanSupplier halted, Predicate<Command> output, Projections projections, FactListener facts, StateView view, ResultPublisher results, StepListener steps, Evictor evictor) {
        return new Cleanroom(kernel, halted, output, projections, facts, view, results, steps, evictor, Release.IMMEDIATE, Snapshotting.NEVER, envelope -> { });
    }
}
