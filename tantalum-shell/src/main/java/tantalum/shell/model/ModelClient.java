package tantalum.shell.model;

import tantalum.value.model.Prompt;
import tantalum.value.model.RequestOptions;

/** 与供应商无关的流式模型调用。不抛业务异常，失败和中断都在返回值里；不自动重试，重试由内核负责。 */
@FunctionalInterface
public interface ModelClient {

    ModelOutcome call(Prompt prompt, ChunkListener chunks);

    /**
     * 带附件内容的调用。prompt 里的附件引用按 {@code media} 取内容；不认附件的客户端（如脚本化模型）忽略它。
     * 取不到附件时抛 {@link AttachmentMedia.Unavailable}，由调用方让这次调用失败。
     */
    default ModelOutcome call(Prompt prompt, AttachmentMedia media, ChunkListener chunks) {
        return call(prompt, chunks);
    }

    /** 从哪读这个模型的上下文窗口（compaction-threshold T1）；默认读不到，用配置的值。 */
    default WindowSource window() {
        return WindowSource.NONE;
    }

    /** 这个 prompt 会发出的请求选项，记进结算事件供轨迹显示。必须是纯函数，不含密钥和地址；默认 null，显示为“未记录”。 */
    default RequestOptions options(Prompt prompt) {
        return null;
    }
}
