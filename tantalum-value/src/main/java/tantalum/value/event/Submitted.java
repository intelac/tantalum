package tantalum.value.event;

import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/** 用户提交的事件：占用一个入口名额，按请求 ID 去重。 */
public interface Submitted extends Event {

    SubmissionId submission();

    RequestId request();
}
