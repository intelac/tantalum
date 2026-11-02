package tantalum.shell.loop;

import com.lmax.disruptor.EventHandler;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import tantalum.kernel.Kernel;
import tantalum.shell.conversation.Projection;
import tantalum.shell.handler.Handler;
import tantalum.shell.journal.Sequence;
import tantalum.value.command.Command;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;

/** 管线的测试替身和简化装配。生产代码总是给全依赖，这些只给测试用，所以不放在 main 里。 */
public final class LoopFixtures {

    /** 从不回收、从不写快照。 */
    public static final Evictor.Settings NEVER = new Evictor.Settings(null, Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofSeconds(1));

    public static final Evictor.Sink NO_SINK = slice -> { };

    public static final Projection NO_PROJECTION = (envelope, decision) -> List.of();

    public static final StepListener NO_STEPS = (envelope, before, after) -> { };

    public static final UserLane.Loader NO_LOADER = loader((principal, session, beforeSeq) -> Optional.empty());

    /** 只载入会话的 {@link UserLane.Loader}，测试里可以写成 lambda。 */
    @FunctionalInterface
    public interface Load {
        Optional<CleanroomRing.Loaded> load(Principal principal, SessionId session, long beforeSeq) throws Exception;
    }

    /** 假 {@link Handler} 的主体，测试里写成 lambda。 */
    @FunctionalInterface
    public interface HandlerBody {
        Event run(Command command) throws Exception;
    }

    private LoopFixtures() {}

    /** 不带用量：用量为 null 时通道不并入。 */
    public static UserLane.Loader loader(Load load) {
        return new UserLane.Loader() {
            @Override
            public Optional<CleanroomRing.Loaded> load(Principal principal, SessionId session, long beforeSeq) throws Exception {
                return load.load(principal, session, beforeSeq);
            }

            @Override
            public PrincipalState usage(Principal principal, long beforeSeq) {
                return null;
            }
        };
    }

    public static Evictor never(Projection projection) {
        return new Evictor(NEVER, new Residency(), projection, NO_SINK, Clock.systemUTC());
    }

    /** 不监听步骤、不回收的 cleanroom。 */
    public static Cleanroom cleanroom(Kernel kernel, BooleanSupplier halted, Predicate<Command> output,
                                      Projection projection, FactListener facts, StateView view, ResultPublisher results) {
        return new Cleanroom(kernel, halted, output, projection, facts, view, results, NO_STEPS, never(projection));
    }

    /** 不载入冷会话的分发器，下游只看事件。 */
    public static LaneDispatcher dispatcher(int capacity, Sequence sequence, UserLane.Writer writer, Predicate<EventEnvelope> cleanroom) {
        return new LaneDispatcher(capacity, sequence, writer, (envelope, usage, load) -> cleanroom.test(envelope), NO_LOADER, new Residency());
    }

    /** 建环时就接好消费者。 */
    public static InputRing inputRing(int size, EventHandler<InputRing.Slot> consumer) {
        InputRing ring = new InputRing(size);
        ring.connect(consumer);
        return ring;
    }
}
