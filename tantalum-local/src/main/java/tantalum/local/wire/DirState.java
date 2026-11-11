package tantalum.local.wire;

/** 用户填的技能目录、wiki 目录在这台电脑上的情况。 */
public enum DirState {
    OK,
    /** 在允许的范围里，但还不存在（或不是目录）。 */
    MISSING,
    /** 不在连接程序允许的目录里。存不存在都是这个回答，服务器不能借它探测范围外的路径。 */
    OUTSIDE,
    /** 在范围里也存在，但列不出来。 */
    UNREADABLE
}
