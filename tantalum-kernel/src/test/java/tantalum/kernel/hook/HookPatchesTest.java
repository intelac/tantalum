package tantalum.kernel.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookDecided;
import tantalum.value.decision.HookReported;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.PromptPatch;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionContext.HookState;
import tantalum.value.state.SessionContext.HookState.HookTool;
import tantalum.value.state.SessionState;

/** hook-patch 需求 1、2：补丁变成追加的 system 消息，变化才追加，工具以 deferred 定义加工具变化表达。 */
class HookPatchesTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final RunRef RUN = new RunRef(USER, SESSION, new RunId("s_0"));
    private static final ToolSpec GREP = new ToolSpec("grep", "d", new Json.Null());
    private static final ToolSpec CITE = new ToolSpec("cite", "cite sources", new Json.Null());
    private static final ToolSpec LOOKUP = new ToolSpec("lookup", "look up", new Json.Null());

    private static SessionState session() {
        return SessionState.start(SESSION, USER)
                .withContext(new SessionContext("system", List.of(GREP), null, List.of()))
                .append(Message.user("hi"));
    }

    private static Fact patch(String hook, int chars, ToolSpec... tools) {
        return new HookDecided(RUN, hook, HookPoint.BEFORE_MODEL_CALL, HookDecided.Kind.PATCH, null, chars,
                List.of(tools).stream().map(ToolSpec::name).toList(), null, null);
    }

    private static Message system(ContentBlock... blocks) {
        return new Message(Role.SYSTEM, List.of(blocks), false);
    }

    private static HookState hooks(SessionState session) {
        return session.context().hookState();
    }

    @Test
    void theFirstTextIsAppendedAsASystemMessageAndTheSystemStringIsUntouched() {
        SessionState before = session();
        HookPatches.Applied applied = HookPatches.apply(before, List.of(patch("a", 3), patch("b", 4)),
                new PromptPatch("onetwo!", List.of()), RUN);
        assertEquals(List.of(Message.user("hi"), system(new HookTextBlock("a", "one"), new HookTextBlock("b", "two!"))),
                applied.session().messages());
        assertEquals("system", applied.session().context().system());
        assertEquals(new HookState(List.of(new HookTextBlock("a", "one"), new HookTextBlock("b", "two!")), List.of(), List.of()), hooks(applied.session()));
        assertEquals(List.of(), applied.facts());
    }

    @Test
    void theSameTextAgainAppendsNothing() {
        SessionState once = HookPatches.apply(session(), List.of(patch("a", 5)), new PromptPatch("brief", List.of()), RUN).session();
        HookPatches.Applied again = HookPatches.apply(once, List.of(patch("a", 5)), new PromptPatch("brief", List.of()), RUN);
        assertSame(once, again.session());
    }

    @Test
    void aChangedTextIsAppendedAgainAndTheOldOneStays() {
        SessionState once = HookPatches.apply(session(), List.of(patch("q", 3)), new PromptPatch("80%", List.of()), RUN).session();
        SessionState twice = HookPatches.apply(once.append(new Message(Role.ASSISTANT, List.of(new TextBlock("ok")), false))
                .append(Message.user("more")), List.of(patch("q", 3)), new PromptPatch("40%", List.of()), RUN).session();
        assertEquals(List.of(Message.user("hi"), system(new HookTextBlock("q", "80%")),
                new Message(Role.ASSISTANT, List.of(new TextBlock("ok")), false), Message.user("more"),
                system(new HookTextBlock("q", "40%"))), twice.messages());
        assertEquals(List.of(new HookTextBlock("q", "40%")), hooks(twice).texts());
    }

    /** 需求 1.3：说明变空（插件被关掉）不追加任何东西，但记下现在是空，之后再出现同样的文字要重发。 */
    @Test
    void anEmptiedTextAppendsNothingButIsRemembered() {
        SessionState once = HookPatches.apply(session(), List.of(patch("q", 3)), new PromptPatch("80%", List.of()), RUN).session();
        SessionState gone = HookPatches.apply(once, List.of(), PromptPatch.NONE, RUN).session();
        assertEquals(once.messages(), gone.messages());
        assertEquals(List.of(), hooks(gone).texts());
        SessionState back = HookPatches.apply(gone, List.of(patch("q", 3)), new PromptPatch("80%", List.of()), RUN).session();
        assertEquals(once.messages().size() + 1, back.messages().size());
    }

    /** 需求 1.4：压缩后重新冻结的上下文没有钩子状态，说明重新追加。 */
    @Test
    void afterCompactionTheTextIsAppendedAgain() {
        SessionState once = HookPatches.apply(session(), List.of(patch("q", 5)), new PromptPatch("brief", List.of()), RUN).session();
        SessionState refrozen = once.withContext(new SessionContext("system", List.of(GREP), null, List.of()));
        SessionState again = HookPatches.apply(refrozen, List.of(patch("q", 5)), new PromptPatch("brief", List.of()), RUN).session();
        assertEquals(once.messages().size() + 1, again.messages().size());
        assertEquals(system(new HookTextBlock("q", "brief")), again.messages().getLast());
    }

    @Test
    void aNewToolIsRememberedAsADefinitionAndAnnouncedAsAnAddition() {
        HookPatches.Applied applied = HookPatches.apply(session(), List.of(patch("tools", 0, CITE)),
                new PromptPatch("", List.of(CITE)), RUN);
        assertEquals(system(new ToolChangeBlock(List.of("cite"), List.of())), applied.session().messages().getLast());
        assertEquals(new HookState(List.of(), List.of(new HookTool("tools", CITE)), List.of("cite")), hooks(applied.session()));
    }

    /** 需求 2.2：同名工具再出现沿用第一次的定义。 */
    @Test
    void aSameNameToolKeepsItsFirstDefinition() {
        SessionState once = HookPatches.apply(session(), List.of(patch("tools", 0, CITE)), new PromptPatch("", List.of(CITE)), RUN).session();
        ToolSpec changed = new ToolSpec("cite", "cite differently", new Json.Null());
        SessionState again = HookPatches.apply(once, List.of(patch("tools", 0, changed)), new PromptPatch("", List.of(changed)), RUN).session();
        assertEquals(List.of(new HookTool("tools", CITE)), hooks(again).tools());
        assertEquals(once.messages(), again.messages());
    }

    /** 需求 2.3：上次加的工具这次不在补丁里就撤回；定义留着。 */
    @Test
    void aToolNoLongerOfferedIsAnnouncedAsARemoval() {
        SessionState once = HookPatches.apply(session(), List.of(patch("tools", 0, CITE, LOOKUP)),
                new PromptPatch("", List.of(CITE, LOOKUP)), RUN).session();
        SessionState less = HookPatches.apply(once, List.of(patch("tools", 0, LOOKUP)), new PromptPatch("", List.of(LOOKUP)), RUN).session();
        assertEquals(system(new ToolChangeBlock(List.of(), List.of("cite"))), less.messages().getLast());
        assertEquals(List.of("lookup"), hooks(less).active());
        assertEquals(2, hooks(less).tools().size());
    }

    /** 需求 2.4：说明和工具变化放在同一条消息里，文字在前。 */
    @Test
    void textAndToolsShareOneMessage() {
        HookPatches.Applied applied = HookPatches.apply(session(), List.of(patch("a", 5, CITE)),
                new PromptPatch("brief", List.of(CITE)), RUN);
        assertEquals(system(new HookTextBlock("a", "brief"), new ToolChangeBlock(List.of("cite"), List.of())),
                applied.session().messages().getLast());
    }

    @Test
    void afterTheAssistantNothingIsAppendedAndItIsReported() {
        SessionState odd = session().append(new Message(Role.ASSISTANT, List.of(new TextBlock("ok")), false));
        HookPatches.Applied applied = HookPatches.apply(odd, List.of(patch("a", 5)), new PromptPatch("brief", List.of()), RUN);
        assertSame(odd, applied.session());
        assertEquals(List.of(new HookReported(RUN, "hook-patch", "patch skipped: the last message is from the assistant")), applied.facts());
    }

    @Test
    void aTextThatDoesNotMatchTheFactsIsKeptWhole() {
        HookPatches.Applied applied = HookPatches.apply(session(), List.of(patch("a", 2)), new PromptPatch("brief", List.of()), RUN);
        assertEquals(system(new HookTextBlock("?", "brief")), applied.session().messages().getLast());
    }

    /** 和服务器上已有的工具重名：全集里有它，开关归插件页，钩子的这条忽略。 */
    @Test
    void aToolAlreadyOnTheServerIsIgnored() {
        HookPatches.Applied applied = HookPatches.apply(session(), List.of(patch("tools", 0, GREP, CITE)),
                new PromptPatch("", List.of(GREP, CITE)), RUN);
        assertEquals(system(new ToolChangeBlock(List.of("cite"), List.of())), applied.session().messages().getLast());
        assertEquals(new HookState(List.of(), List.of(new HookTool("tools", CITE)), List.of("cite")), hooks(applied.session()));
    }

    @Test
    void withoutAFrozenContextNothingHappens() {
        SessionState bare = SessionState.start(SESSION, USER).append(Message.user("hi"));
        assertSame(bare, HookPatches.apply(bare, List.of(patch("a", 5)), new PromptPatch("brief", List.of()), RUN).session());
    }

    /** review-fixes 需求 7.1：只有一个钩子的说明变了时，只重发它那一块。 */
    @Test
    void onlyTheChangedHooksTextIsSentAgain() {
        SessionState once = HookPatches.apply(session(), List.of(patch("a", 3), patch("b", 4)), new PromptPatch("onetwo!", List.of()), RUN).session();
        SessionState again = HookPatches.apply(once, List.of(patch("a", 3), patch("b", 5)), new PromptPatch("onethree", List.of()), RUN).session();
        assertEquals(system(new HookTextBlock("b", "three")), again.messages().getLast());
        assertEquals(List.of(new HookTextBlock("a", "one"), new HookTextBlock("b", "three")), hooks(again).texts());
    }
}
