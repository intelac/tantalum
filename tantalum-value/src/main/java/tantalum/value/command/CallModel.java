package tantalum.value.command;

import java.util.List;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Prompt;

/** 调用模型，携带完整输入。{@code omittedImages} 是这次请求里因图片预算省略的附件 ID，只供轨迹显示。 */
public record CallModel(CommandId id, RunRef run, Prompt prompt, List<String> omittedImages) implements Command {

    public CallModel {
        omittedImages = omittedImages == null ? List.of() : List.copyOf(omittedImages);
    }

    public CallModel(CommandId id, RunRef run, Prompt prompt) {
        this(id, run, prompt, List.of());
    }
}
