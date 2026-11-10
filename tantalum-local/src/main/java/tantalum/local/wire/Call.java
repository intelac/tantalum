package tantalum.local.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Objects;
import tantalum.value.json.Json;

/**
 * 服务器→电脑：执行一次工具调用。{@code arguments} 原样是模型给的参数；{@code timeoutMs} 到了服务器就不等了，会另发 {@link Cancel}。
 *
 * @param id 这次调用的编号，{@link Result} 和 {@link Cancel} 用它对应
 * @param user 替哪个用户执行，必须和 {@link Welcome#user()} 相同
 * @param session 哪个会话，连接程序的终端记录里写出来
 * @param dir 会话的工作目录（协议 3 起），相对路径和命令的起始目录从这里算；没有时为 null，不写出，连接程序用主目录
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Call(String id, String user, String session, String tool, Json arguments, long timeoutMs, String dir) implements Message {

    public Call {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(tool, "tool");
        arguments = arguments == null ? new Json.Obj(Map.of()) : arguments;
    }

    /** 没有会话目录时。 */
    public Call(String id, String user, String session, String tool, Json arguments, long timeoutMs) {
        this(id, user, session, tool, arguments, timeoutMs, null);
    }
}
