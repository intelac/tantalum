package tantalum.shell.model;

import tantalum.value.model.Prompt;
import tantalum.value.model.RequestOptions;

/** 与供应商无关的流式模型调用。不抛业务异常，失败和中断都在返回值里；不自动重试，重试由内核负责。 */
@FunctionalInterface
public interface ModelClient {

    ModelOutcome call(Prompt prompt, ChunkListener chunks);

    /** 这个 prompt 会发出的请求选项，记进结算事件供轨迹显示。必须是纯函数，不含密钥和地址；默认 null，显示为“未记录”。 */
    default RequestOptions options(Prompt prompt) {
        return null;
    }
}
