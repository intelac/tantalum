package tantalum.value.model;

/** 消息里的一段内容，与模型供应商无关。 */
public sealed interface ContentBlock permits TextBlock, ReasoningBlock, ToolCallBlock, ToolResultBlock, AttachmentBlock,
        ToolChangeBlock {}
