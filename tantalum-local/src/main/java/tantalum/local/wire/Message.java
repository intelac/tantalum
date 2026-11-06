package tantalum.local.wire;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 连接程序和服务器之间的一条消息：一个 JSON 文本帧，{@code type} 说明是哪一种（local-workspace 设计“tantalum.local.wire”）。
 * 令牌只在握手的 {@code Authorization} 头里，任何消息都没有令牌字段（L18）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = Welcome.class, name = "welcome"),
        @JsonSubTypes.Type(value = Call.class, name = "call"),
        @JsonSubTypes.Type(value = Result.class, name = "result"),
        @JsonSubTypes.Type(value = Cancel.class, name = "cancel"),
        @JsonSubTypes.Type(value = Context.class, name = "context"),
        @JsonSubTypes.Type(value = ContextResult.class, name = "contextResult"),
        @JsonSubTypes.Type(value = Bye.class, name = "bye")})
public sealed interface Message permits Hello, Welcome, Call, Result, Cancel, Context, ContextResult, Bye {}
