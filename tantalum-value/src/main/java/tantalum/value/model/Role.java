package tantalum.value.model;

/** 消息的角色。{@code SYSTEM} 是会话中途追加的系统消息（目前只有工具变化），不是用户写的，也不是模型说的。 */
public enum Role {
    USER,
    ASSISTANT,
    SYSTEM
}
