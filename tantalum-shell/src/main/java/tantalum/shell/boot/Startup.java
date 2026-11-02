package tantalum.shell.boot;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.shell.conversation.SessionSummary;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.store.SessionIndex;
import tantalum.shell.store.SessionReplay;
import tantalum.shell.store.SessionStore;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;

/**
 * 启动时的内存状态。平常只载入索引里有未结束运行的会话，其余用到时再载入；索引补不上时退回全量重放。
 * 未结束的运行由 {@code Engine} 随后发布 {@code RunInterrupted} 收尾，这里不改状态。
 */
public final class Startup {

    private static final Logger LOG = LoggerFactory.getLogger(Startup.class);

    /** {@code failed} 是载入失败的会话和原因，它们到下次重启前都被拒绝。 */
    public record Recovered(KernelState state, Transcript transcript, Map<SessionId, String> failed) {

        public Recovered(KernelState state, Transcript transcript) {
            this(state, transcript, Map.of());
        }

        /** 重启前没有结束的运行。 */
        public List<RunState> unfinished() {
            return state.runs().values().stream().filter(run -> !run.ended()).toList();
        }
    }

    private Startup() {}

    /** 全量重放全部会话文件。 */
    public static Recovered recover(SessionStore store, Kernel kernel) {
        List<EventEnvelope> events = store.readAll();
        Transcript transcript = new Transcript();
        return new Recovered(transcript.replay(kernel, events), transcript);
    }

    /** 只载入有未结束运行的会话。文件坏了只拒绝那一个会话，不拦住启动。 */
    public static Recovered resume(SessionIndex index, SessionReplay replay) throws SQLException, InterruptedException {
        KernelState state = KernelState.empty();
        Transcript transcript = new Transcript();
        Map<SessionId, String> failed = new LinkedHashMap<>();
        for (SessionIndex.Session session : index.sessions()) {
            if (SessionSummary.IDLE.equals(session.runStatus())) {
                continue;
            }
            try {
                Optional<SessionReplay.Slice> slice = replay.load(session.principal(), session.session(), Long.MAX_VALUE);
                if (slice.isPresent()) {
                    state = state.withResident(slice.get().snapshot().session(), slice.get().snapshot().runs());
                    transcript.restore(session.session(), slice.get().snapshot().transcript());
                }
            } catch (InterruptedException stopping) {
                throw stopping;
            } catch (Exception failure) {
                LOG.error("cannot load session {} with an unfinished run; refusing it until restart", session.session().value(), failure);
                failed.put(session.session(), failure.toString());
            }
        }
        return new Recovered(state, transcript, failed);
    }
}
