package tantalum.local.wire;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** 服务器→电脑：马上要关这条连接了，为什么。连接程序据此在终端写明原因。 */
public record Bye(Reason reason) implements Message {

    public Bye {
        Objects.requireNonNull(reason, "reason");
    }

    public enum Reason {
        /** 令牌到期。 */
        @JsonProperty("expired") EXPIRED,
        /** 用户在网页上撤销了。 */
        @JsonProperty("revoked") REVOKED,
        /** 另一台电脑用这个用户连上了，顶替了这条。 */
        @JsonProperty("replaced") REPLACED,
        /** 服务器关机。 */
        @JsonProperty("shutdown") SHUTDOWN
    }
}
