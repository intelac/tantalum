package tantalum.local.wire;

import java.time.Instant;
import java.util.Objects;

/** 服务器→电脑：握手通过，这条连接属于哪个用户、令牌什么时候到期。之后每个 {@link Call} 都带这个用户，连接程序逐个核对（L18）。 */
public record Welcome(String user, Instant expiresAt) implements Message {

    public Welcome {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
