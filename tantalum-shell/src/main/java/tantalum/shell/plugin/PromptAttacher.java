package tantalum.shell.plugin;

import java.util.List;
import java.util.Optional;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 提交时附加内容（memory-plugin 需求 1.2）：用户提问（新的一轮、排队提交，不含插话）时，插件可以往这一轮的用户消息上附一段文字。
 * 有时间上限，超时就这一轮不附。附的文字随提交写进 journal，发给模型时包在 {@code <attached plugin="…">} 里接在用户的话后面。
 */
public interface PromptAttacher {

    String plugin();

    /** 要附的文字；不附时为空。在虚拟线程上调用，可以阻塞，但超过时限的结果会被丢掉。 */
    Optional<String> attach(Submission submission);

    /** 这一次提交：谁、哪个会话、说了什么，以及这个插件在这个会话里之前附过的文字（去重用）。 */
    record Submission(Principal user, SessionId session, String text, List<String> attachedBefore) {

        public Submission {
            attachedBefore = List.copyOf(attachedBefore);
        }
    }
}
