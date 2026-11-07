package tantalum.value.decision;

import java.util.List;
import java.util.Objects;
import tantalum.value.id.RunRef;

/**
 * 组 prompt 时，这些图片因超出请求的图片预算第一次被省略。以后的请求里它们一直省略，不再重复报。
 * 不写 journal，回放时重新算出。
 */
public record ImagesOmitted(RunRef run, List<String> attachmentIds) implements Fact {

    public ImagesOmitted {
        Objects.requireNonNull(run, "run");
        attachmentIds = List.copyOf(attachmentIds);
    }

    @Override
    public String type() {
        return "ImagesOmitted";
    }
}
