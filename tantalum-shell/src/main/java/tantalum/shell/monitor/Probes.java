package tantalum.shell.monitor;

import java.util.function.Supplier;
import tantalum.value.command.Command;
import tantalum.value.command.RunTool;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ResultEvent;

/** 埋点的一行写法：有人看才取说明（{@code detail} 是延迟算的）。 */
public final class Probes {

    private Probes() {}

    /** 一个事件到了某个阶段：名字是事件类型，处理器的结果带上命令 ID。 */
    public static void event(Probe probe, Stage stage, EventEnvelope envelope, Supplier<String> detail) {
        if (!probe.watching(envelope.principal())) {
            return;
        }
        String command = envelope.event() instanceof ResultEvent result && result.command() != null ? result.command().value() : null;
        Long seq = envelope.seq() == EventEnvelope.UNASSIGNED ? null : envelope.seq();
        probe.at(envelope.principal(), stage, envelope.session(), envelope.run(), seq, command, envelope.event().getClass().getSimpleName(),
                detail == null ? null : detail.get());
    }

    /** 一个命令到了某个阶段：名字是命令类型，工具调用用工具名。 */
    public static void command(Probe probe, Stage stage, Command command, Supplier<String> detail) {
        if (!probe.watching(command.run().principal())) {
            return;
        }
        probe.at(command.run().principal(), stage, command.run().session(), command.run().run(), null, command.id().value(), name(command),
                detail == null ? null : detail.get());
    }

    static String name(Command command) {
        return command instanceof RunTool tool ? tool.call().name() : command.getClass().getSimpleName();
    }
}
