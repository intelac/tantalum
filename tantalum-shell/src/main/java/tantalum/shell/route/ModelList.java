package tantalum.shell.route;

import java.io.IOException;
import java.util.List;

/** 读一条路线现在提供哪些模型。网络和协议错误抛 {@link Unreadable}，由 {@link Routes} 记日志、保留上次的结果。 */
@FunctionalInterface
public interface ModelList {

    List<ModelInfo> read() throws IOException, InterruptedException;

    /** 读不到。说明是我们自己写的类别（代码 {@code unreachable}、{@code timeout}、{@code not-a-list}、{@code not-yet} 或 HTTP 状态码，页面按语言显示；QAgent 问题 86），不含地址、请求头和响应体，可以进日志和画面。 */
    final class Unreadable extends IOException {
        public Unreadable(String category) {
            super(category);
        }
    }
}
