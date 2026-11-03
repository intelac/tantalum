package tantalum.value.model;

import java.util.Objects;
import tantalum.value.attachment.AttachmentRef;

/** 用户消息里的一个附件。内容不在这里：发请求时由客户端按引用去本人的附件目录取（设计第 1、4 节）。 */
public record AttachmentBlock(AttachmentRef ref) implements ContentBlock {

    public AttachmentBlock {
        Objects.requireNonNull(ref, "ref");
    }
}
