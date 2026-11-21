package tantalum.value.event;

/** 你说的一句话在会话正忙时怎么送：排在后面等这一轮结束，或者并进正在跑的这一轮。空闲时两种都是直接开一轮。 */
public enum Delivery {
    QUEUE,
    JOIN
}
