package tantalum.local.wire;

import java.util.List;
import java.util.Objects;

/**
 * 电脑→服务器，在 {@link ContextResult} 里（协议 4 起）：用户填的技能目录里有什么。{@code dir} 是目录的真实路径，
 * {@code state} 不是 {@link DirState#OK} 时为 null、其余为空。{@code items} 按名字排序；没通过校验的在 {@code problems} 里，不在 {@code items} 里。
 */
public record SkillList(String dir, DirState state, List<Item> items, List<Problem> problems) {

    public SkillList {
        Objects.requireNonNull(state, "state");
        items = items == null ? List.of() : List.copyOf(items);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** 目录不能用。 */
    public static SkillList unusable(DirState state) {
        return new SkillList(null, state, List.of(), List.of());
    }

    /**
     * {@code model}：模型能不能自己加载；{@code user}：用户能不能用 {@code /name} 点名。
     * 技能被当作任务的 agent 用时（subagent）：{@code tools} 是这个任务能用的工具名（属性块里的 {@code tools: a, b}），
     * {@code taskModel} 是它用的模型（属性块里的 {@code model: 路线/模型}）。没写的不出现，旧的连接程序也不送。
     */
    public record Item(String name, String description, boolean model, boolean user,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<String> tools,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String taskModel) {

        public Item {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            tools = tools == null ? null : List.copyOf(tools);
        }
    }

    /**
     * 一个没加载的技能。{@code folder} 是子目录名；{@code code} 是原因的代码，控制台按看的人的语言说：
     * {@code name-invalid}、{@code name-duplicate}、{@code description-missing}、{@code flag-not-boolean}、{@code yaml-invalid}、
     * {@code not-text}、{@code too-large}、{@code unreadable}、{@code outside}。{@code detail} 是补充（如重名的那个名字），可为 null。
     */
    public record Problem(String folder, String code, String detail) {

        public Problem {
            Objects.requireNonNull(folder, "folder");
            Objects.requireNonNull(code, "code");
        }
    }
}
