package tantalum.shell.store;

import tantalum.shell.loop.SessionSlice;

/** 一个会话截至 {@code slice.seq()} 的状态，{@code offset} 是那个事件那一行结束的文件位置。可丢弃：丢了就全量重放。不含用户级用量，重放尾部时仍按序号注入。 */
public record SessionSnapshot(SessionSlice slice, long offset) {}
