package tantalum.shell.loop;

import tantalum.shell.journal.Snapshot;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 净室在一步之后问的两件事：这个会话要不要一份快照（存储举旗、取一次就放下），要的话把它此刻的切片编成快照。
 * 什么时候要由存储定，内核不知道。
 */
public interface Snapshotting {

    boolean wanted(Principal principal, SessionId session);

    Snapshot of(SessionSlice slice);

    /** 从不写快照：只测内核和投影、不关心账的测试用。 */
    Snapshotting NEVER = new Snapshotting() {
        @Override
        public boolean wanted(Principal principal, SessionId session) {
            return false;
        }

        @Override
        public Snapshot of(SessionSlice slice) {
            throw new IllegalStateException("never asked");
        }
    };
}
