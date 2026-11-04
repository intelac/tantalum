package tantalum.shell.trace;

import java.util.ArrayList;
import java.util.List;
import tantalum.kernel.projection.ContextDiff;
import tantalum.kernel.step.CompactSteps;
import tantalum.shell.trace.Trace.Book;
import tantalum.shell.trace.Trace.Split;
import tantalum.shell.trace.Trace.Track;
import tantalum.shell.trace.TracePass.Place;
import tantalum.shell.trace.TraceRecord.ContextChange;
import tantalum.shell.trace.TraceRecord.Section;
import tantalum.shell.trace.TraceRecord.Segment;
import tantalum.shell.trace.TraceRecord.Source;
import tantalum.shell.trace.TraceRecord.SystemPrompt;
import tantalum.value.command.CallModel;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookDecided;
import tantalum.value.event.ContextAssembled;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.RunId;
import tantalum.value.model.Prompt;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.Awaiting;
import tantalum.value.state.ContextSection;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;

/** system 和工具的变化、它们各段的来源，以及上下文的变化（需求 4）。 */
final class TraceSystem {

    private final TracePass pass;

    TraceSystem(TracePass pass) {
        this.pass = pass;
    }

    /** 有效 system 或工具和上一次请求不同时记一行；会话第一次请求是“初始”。 */
    void called(CallModel call, Split split, Place place) {
        Prompt prompt = call.prompt();
        Book book = pass.book;
        boolean initial = book.system == null;
        if (initial || !prompt.system().equals(book.system) || !prompt.tools().equals(book.tools)) {
            List<String> now = names(prompt.tools());
            List<String> earlier = initial ? List.of() : names(book.tools);
            List<String> added = now.stream().filter(name -> !earlier.contains(name)).toList();
            List<String> removed = earlier.stream().filter(name -> !now.contains(name)).toList();
            pass.add(new SystemPrompt(pass.head("s:" + pass.seq, call.run().run(), place, pass.at).ended(pass.at), initial,
                    prompt.system().length(), split.system(), added, removed, prompt.tools().size(), call.id().value()), place, false);
        }
        book.system = prompt.system();
        book.tools = prompt.tools();
    }

    private static List<String> names(List<ToolSpec> tools) {
        return tools.stream().map(ToolSpec::name).toList();
    }

    /**
     * 有效 system = 会话 system + 模型调用前各钩子按注册顺序追加的补丁，工具同理（{@code PromptProjection}）。
     * 长度和工具名对得上才标钩子来源，对不上的部分标未记录来源。摘要重试不重跑钩子，沿用上一次摘要调用的切分。
     */
    Split split(Prompt prompt, RunId run, Track track) {
        boolean summaryRetry = pass.before.runs().get(run) instanceof RunState earlier && earlier.phase() instanceof Awaiting awaiting
                && awaiting.resumeAt().equals(CompactSteps.AWAITING_SUMMARY_RETRY);
        if (summaryRetry && track.summarySplit != null) {
            return track.summarySplit;
        }
        SessionState session = pass.state.sessions().get(pass.session);
        SessionContext context = session == null ? null : session.context();
        String base = context == null ? "" : context.system();
        List<ToolSpec> baseTools = context == null ? List.of() : context.tools();
        List<HookDecided> patches = new ArrayList<>();
        for (Fact fact : pass.decision.facts()) {
            if (fact instanceof HookDecided decided && decided.kind() == HookDecided.Kind.PATCH
                    && decided.point() == HookPoint.BEFORE_MODEL_CALL && decided.run().run().equals(run)) {
                patches.add(decided);
            }
        }
        String system = prompt.system();
        int extra = patches.stream().mapToInt(HookDecided::systemChars).sum();
        List<Segment> segments = new ArrayList<>();
        if (system.startsWith(base)) {
            segments.add(new Segment(base.length(), Source.CONTEXT));
            if (system.length() == base.length() + extra) {
                patches.stream().filter(patch -> patch.systemChars() > 0)
                        .forEach(patch -> segments.add(new Segment(patch.systemChars(), Source.plugin(patch.hook()))));
            } else if (system.length() > base.length()) {
                segments.add(new Segment(system.length() - base.length(), Source.UNKNOWN));
            }
        } else {
            segments.add(new Segment(system.length(), Source.UNKNOWN));
        }
        List<String> expected = new ArrayList<>(names(baseTools));
        List<Source> sources = new ArrayList<>(baseTools.stream().map(tool -> Source.CONTEXT).toList());
        for (HookDecided patch : patches) {
            expected.addAll(patch.tools());
            patch.tools().forEach(tool -> sources.add(Source.plugin(patch.hook())));
        }
        if (!expected.equals(names(prompt.tools()))) {
            sources.clear();
            prompt.tools().forEach(tool -> sources.add(Source.UNKNOWN));
        }
        return new Split(segments, sources);
    }

    /**
     * 已冻结上下文后再组装：可变的段有变化时内核追加了一条更新消息，记下变了哪几段、之前和之后；用户开着的工具变了时内核追加了
     * 一条工具变化，记下新增和移除了哪些（算法同 {@code ContextSteps}）。
     */
    void contextAssembled(ContextAssembled assembled) {
        SessionState earlier = pass.before.sessions().get(pass.session);
        if (!pass.awaited(assembled) || earlier == null || earlier.context() == null) {
            return;
        }
        List<ContextSection> old = earlier.context().sections();
        SessionContext frozen = earlier.context();
        List<String> all = frozen.tools().stream().map(ToolSpec::name).toList();
        List<String> active = assembled.enabledTools() == null ? all : all.stream().filter(assembled.enabledTools()::contains).toList();
        List<String> added = active.stream().filter(name -> !frozen.toolActive(name)).toList();
        List<String> removed = all.stream().filter(name -> frozen.toolActive(name) && !active.contains(name)).toList();
        if (ContextDiff.of(old, assembled.sections()).isEmpty() && added.isEmpty() && removed.isEmpty()) {
            return;
        }
        List<Section> sections = new ArrayList<>();
        for (ContextSection section : assembled.sections()) {
            ContextSection previous = old.stream().filter(s -> s.name().equals(section.name())).findFirst().orElse(null);
            if (previous == null || !previous.body().equals(section.body())) {
                sections.add(new Section(section.name(), previous == null ? null : previous.body(), section.body()));
            }
        }
        for (ContextSection section : old) {
            if (assembled.sections().stream().noneMatch(s -> s.name().equals(section.name()))) {
                sections.add(new Section(section.name(), section.body(), null));
            }
        }
        RunId run = pass.envelope.run();
        Place place = pass.pending(run);
        pass.add(new ContextChange(pass.head("c:" + pass.seq, run, place, pass.at).ended(pass.at), sections, added, removed), place, false);
    }
}
