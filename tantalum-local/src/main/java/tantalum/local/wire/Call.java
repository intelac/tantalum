package tantalum.local.wire;

import java.util.Map;
import java.util.Objects;
import tantalum.value.json.Json;

/**
 * 服务器→电脑：执行一次工具调用。{@code arguments} 原样是模型给的参数；{@code timeoutMs} 到了服务器就不等了，会另发 {@link Cancel}。
 *
 * @param id 这次调用的编号，{@link Result} 和 {@link Cancel} 用它对应
 * @param user 替哪个用户执行，必须和 {@link Welcome#user()} 相同
 * @param session 哪个会话，连接程序的终端记录里写出来
 */
public record Call(String id, String user, String session, String tool, Json arguments, long timeoutMs) implements Message {

    public Call {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(tool, "tool");
        arguments = arguments == null ? new Json.Obj(Map.of()) : arguments;
    }
}
