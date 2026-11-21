package tantalum.value.delivery;

/** 送到另一个会话的东西（delivery 需求 1）：交代、结果或叫停。{@link #id()} 由种类和交代号得出，收方不用新字段也能算出同一个。 */
public sealed interface Parcel permits AssignmentParcel, ReportParcel, StopParcel {

    /** 这次送达的编号：同一个交代在同一个方向上每种最多一条，所以唯一。 */
    String id();

    /** 交代号。 */
    String assignment();
}
