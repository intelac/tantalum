package tantalum.shell.monitor;

import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/**
 * 流水线类看到的埋点（live-monitor 需求 1）。调用方先问 {@link #watching}，有人看才拼说明、调 {@link #at}，
 * 所以没人看时只有一次查表。实现不能阻塞、不能抛出。
 */
public interface Probe {

    /** 什么都不做：测试和没有监视的嵌入使用。 */
    Probe NONE = new Probe() {
        @Override
        public boolean watching(Principal user) {
            return false;
        }

        @Override
        public void at(Principal user, Stage stage, SessionId session, RunId run, Long seq, String command, String name, String detail) {
        }
    };

    /** 这个用户现在有没有人在看。 */
    boolean watching(Principal user);

    /** 报一个阶段；{@code run}、{@code seq}、{@code command}、{@code detail} 可以为 null。 */
    void at(Principal user, Stage stage, SessionId session, RunId run, Long seq, String command, String name, String detail);
}
