package tantalum.shell.model;

import tantalum.value.model.Prompt;

/** 与供应商无关的流式模型调用。不抛业务异常，失败和中断都在返回值里；不自动重试，重试由内核负责。 */
@FunctionalInterface
public interface ModelClient {

    ModelOutcome call(Prompt prompt, ChunkListener chunks);
}
