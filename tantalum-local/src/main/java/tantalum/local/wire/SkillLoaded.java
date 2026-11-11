package tantalum.local.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/** 电脑→服务器：{@link SkillLoad} 里每个名字的结果，顺序相同。 */
public record SkillLoaded(String id, List<Loaded> items) implements Message {

    public SkillLoaded {
        Objects.requireNonNull(id, "id");
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 读到了：{@code content} 是给模型的 {@code <skill_content>} 整块。没有这个技能、或它不让用户点名：两个都为 null，那个词就是普通文字。
     * 有这个技能但读坏了：{@code code} 是原因（同 {@link SkillList.Problem#code()}）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Loaded(String name, String content, String code) {

        public Loaded {
            Objects.requireNonNull(name, "name");
        }
    }
}
