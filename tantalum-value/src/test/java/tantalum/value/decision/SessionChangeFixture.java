package tantalum.value.decision;

import java.util.List;
import java.util.Map;
import static tantalum.value.decision.SessionChange.*;
import tantalum.value.decision.SessionChange.*;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.state.Compaction;
import tantalum.value.state.SessionContext;
import tantalum.value.state.UsageAnchor;
import tantalum.value.state.InboxItem;
import tantalum.value.state.SessionKind;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。新建的会话是普通会话，没有目录，进记忆。 */
public final class SessionChangeFixture {
    private SessionChangeFixture() {}

    public static SessionChange of(SessionId id, Principal principal, boolean created, List<Message> appended, Changed<RunId> activeRun, Changed<RunId> lastRun, Changed<List<InboxItem>> inbox, Changed<SessionContext> context, Changed<Compaction> compaction, Changed<UsageAnchor> anchor, Changed<List<String>> omittedImages) {
        return of(id, principal, created, appended, activeRun, lastRun, inbox, context, compaction, anchor, omittedImages, null, null, null);
    }

    public static SessionChange of(SessionId id, Principal principal, boolean created, List<Message> appended, Changed<RunId> activeRun, Changed<RunId> lastRun, Changed<List<InboxItem>> inbox, Changed<SessionContext> context, Changed<Compaction> compaction, Changed<UsageAnchor> anchor, Changed<List<String>> omittedImages, Changed<List<String>> toolsSeen, Changed<Map<String, Json.Obj>> notes) {
        return of(id, principal, created, appended, activeRun, lastRun, inbox, context, compaction, anchor, omittedImages, toolsSeen, notes, null);
    }

    public static SessionChange of(SessionId id, Principal principal, boolean created, List<Message> appended, Changed<RunId> activeRun, Changed<RunId> lastRun, Changed<List<InboxItem>> inbox, Changed<SessionContext> context, Changed<Compaction> compaction, Changed<UsageAnchor> anchor, Changed<List<String>> omittedImages, Changed<List<String>> toolsSeen, Changed<Map<String, Json.Obj>> notes, Changed<ModelChoice> model) {
        return new SessionChange(id, principal, created, created ? SessionKind.REGULAR : null, null, null, false, appended, null, activeRun, lastRun, inbox,
                context, compaction, anchor, omittedImages, toolsSeen, notes, model, null, null, null);
    }
}
