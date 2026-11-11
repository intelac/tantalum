package tantalum.shell.access;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.plugin.Configurable;
import tantalum.shell.plugin.Param;

/**
 * 把成员声明的参数并成一份。一个钩子插件可以有好几个成员（钩子、工具后处理、插件工作），它们的参数合在一起、按出现的顺序；
 * 同一个键两个成员都声明时必须一模一样，否则启动失败：页面上只有一个框，不知道该听谁的。
 */
final class Declared {

    private Declared() {}

    /** {@code member} 实现了 {@link Configurable} 就把它的参数并进 {@code into}，没实现什么都不做。{@code owner} 只用在报错里。 */
    static void merge(String owner, Object member, Map<String, Param> into) {
        if (!(member instanceof Configurable configurable)) {
            return;
        }
        for (Param param : configurable.params()) {
            Param earlier = into.putIfAbsent(param.key(), param);
            if (earlier != null && !earlier.equals(param)) {
                throw new IllegalArgumentException(owner + " declares the param " + param.key()
                        + " twice with different declarations (" + member.getClass().getName() + ")");
            }
        }
    }

    /** 一个成员自己的一份。 */
    static List<Param> of(String owner, Object member) {
        Map<String, Param> merged = new LinkedHashMap<>();
        merge(owner, member, merged);
        return List.copyOf(new ArrayList<>(merged.values()));
    }
}
