package tantalum.value.event;

import java.util.List;
import static tantalum.value.event.SteerSubmitted.*;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.event.SteerSubmitted.*;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class SteerSubmittedFixture {

    private SteerSubmittedFixture() {}

    public static SteerSubmitted of(SubmissionId submission, RequestId request, String text) {
        return new SteerSubmitted(submission, request, text, List.of(), List.of());
    }

    public static SteerSubmitted of(SubmissionId submission, RequestId request, String text, List<AttachmentRef> attachments) {
        return new SteerSubmitted(submission, request, text, attachments, List.of());
    }
}
