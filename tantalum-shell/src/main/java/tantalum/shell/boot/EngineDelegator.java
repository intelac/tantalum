package tantalum.shell.boot;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tantalum.shell.delegate.Delegations;
import tantalum.shell.tool.DelegateTool;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.state.SessionState;

/** {@link DelegateTool} 要平台做的事：在子会话里提问、看它结束没有、取消、把结果送回父会话（memory-final-plan 第 12 节）。 */
final class EngineDelegator implements DelegateTool.Runner, AutoCloseable {

    private final Engine engine;
    private Delegations delegations;

    EngineDelegator(Engine engine) {
        this.engine = engine;
    }

    @Override
    public int submit(Principal user, SessionId session, String text, String dir, boolean offRecord) {
        return engine.intake().submit(user, session, new RequestId(UUID.randomUUID().toString()), "prompt", text, List.of(), null, dir,
                List.of(), List.of(), offRecord).status();
    }

    @Override
    public Optional<String> finished(Principal user, SessionId session) {
        Optional<SessionState> state = engine.queries().session(user, session).map(view -> view.state());
        if (state.isEmpty() || state.get().activeRun() != null || state.get().lastRun() == null || state.get().messages().isEmpty()) {
            return Optional.empty();
        }
        List<Message> messages = state.get().messages();
        Message last = messages.getLast();
        if (last.role() != Role.ASSISTANT) {
            return Optional.of("");
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : last.content()) {
            if (block instanceof TextBlock part) {
                text.append(part.text());
            }
        }
        return Optional.of(text.toString().strip());
    }

    @Override
    public void cancel(Principal user, SessionId session) {
        engine.queries().session(user, session).map(view -> view.state().activeRun())
                .ifPresent(run -> engine.intake().cancel(user, new RunRef(user, session, run)));
    }

    @Override
    public void report(Principal user, SessionId session, String text) {
        engine.intake().submit(user, session, new RequestId(UUID.randomUUID().toString()), "prompt", text);
    }

    @Override
    public synchronized Delegations delegations() {
        if (delegations == null) {
            try {
                delegations = Delegations.open(engine.dataDir());
            } catch (IOException | SQLException failure) {
                throw new IllegalStateException("cannot open the delegation records", failure);
            }
        }
        return delegations;
    }

    @Override
    public synchronized void close() {
        if (delegations != null) {
            try {
                delegations.close();
            } catch (SQLException ignored) {
                // 关不掉不影响退出
            }
            delegations = null;
        }
    }
}
