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

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class SessionChangeFixture {

    private SessionChangeFixture() {}

    public static SessionChange of(SessionId id, Principal principal, boolean created, List<Message> appended, Changed<RunId> activeRun, Changed<RunId> lastRun, Changed<List<Message>> followUps, Changed<SessionContext> context, Changed<Compaction> compaction, Changed<UsageAnchor> anchor, Changed<List<String>> omittedImages) {
        return new SessionChange(id, principal, created, appended, activeRun, lastRun, followUps, context, compaction, anchor, omittedImages, null, null, null, null, null, null, null, null);
    }

    public static SessionChange of(SessionId id, Principal principal, boolean created, List<Message> appended, Changed<RunId> activeRun, Changed<RunId> lastRun, Changed<List<Message>> followUps, Changed<SessionContext> context, Changed<Compaction> compaction, Changed<UsageAnchor> anchor, Changed<List<String>> omittedImages, Changed<List<String>> toolsSeen, Changed<Map<String, Json.Obj>> notes) {
        return new SessionChange(id, principal, created, appended, activeRun, lastRun, followUps, context, compaction, anchor, omittedImages, toolsSeen, notes, null, null, null, null, null, null);
    }

    public static SessionChange of(SessionId id, Principal principal, boolean created, List<Message> appended, Changed<RunId> activeRun, Changed<RunId> lastRun, Changed<List<Message>> followUps, Changed<SessionContext> context, Changed<Compaction> compaction, Changed<UsageAnchor> anchor, Changed<List<String>> omittedImages, Changed<List<String>> toolsSeen, Changed<Map<String, Json.Obj>> notes, Changed<ModelChoice> model, Changed<List<ModelChoice>> followUpModels) {
        return new SessionChange(id, principal, created, appended, activeRun, lastRun, followUps, context, compaction, anchor, omittedImages, toolsSeen, notes, model, followUpModels, null, null, null, null);
    }
}
