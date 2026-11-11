package tantalum.shell.param;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.shell.param.ParamRules.InvalidParam;
import tantalum.shell.param.ParamStore.Scope;
import tantalum.shell.plugin.Kind;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.Secrets;
import tantalum.value.hook.Params;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

/**
 * 一个参数此刻对某个人是什么值。用户能改的：自己填的 → 管理员定的默认 → 插件自带的。只有管理员能改的：
 * 管理员给这个人指定的 → 管理员定的默认 → 插件自带的。存着的值不再合声明（插件升级后类型或范围变了）就当没存，往下找。
 * 这一项被锁定时，用户自己填的不算（留在库里，解锁后照旧）；密码不受锁定影响。
 */
public final class ParamResolver {

    public enum Source { BUILTIN, ADMIN, OWN, ASSIGNED }

    /** 一个参数算出来的值和它的来历。 */
    public record Resolved(Param param, Json value, Source source) {}

    private final ParamStore store;

    public ParamResolver(ParamStore store) {
        this.store = store;
    }

    /** 没锁定时。 */
    public List<Resolved> resolve(Principal user, Kind kind, String name, List<Param> declared) {
        return resolve(user, kind, name, declared, false);
    }

    /** 声明里不是密码的参数，按声明的顺序。{@code locked} 是这一项的开关被锁定了。 */
    public List<Resolved> resolve(Principal user, Kind kind, String name, List<Param> declared, boolean locked) {
        Map<String, Json> defaults = store.values(Scope.DEFAULT, null, kind, name);
        Map<String, Json> own = store.values(Scope.OWN, user, kind, name);
        Map<String, Json> assigned = store.values(Scope.ASSIGNED, user, kind, name);
        List<Resolved> resolved = new ArrayList<>();
        for (Param param : declared) {
            if (param.secret()) {
                continue;
            }
            boolean adminOnly = param.owner() == Param.Owner.ADMIN;
            Json personal = !adminOnly && locked ? null : valid(param, (adminOnly ? assigned : own).get(param.key()));
            Json byAdmin = valid(param, defaults.get(param.key()));
            if (personal != null) {
                resolved.add(new Resolved(param, personal, adminOnly ? Source.ASSIGNED : Source.OWN));
            } else if (byAdmin != null) {
                resolved.add(new Resolved(param, byAdmin, Source.ADMIN));
            } else {
                resolved.add(new Resolved(param, param.byDefault(), Source.BUILTIN));
            }
        }
        return List.copyOf(resolved);
    }

    /** 所有人的默认：管理员定的 → 插件自带的。 */
    public List<Resolved> defaults(Kind kind, String name, List<Param> declared) {
        Map<String, Json> defaults = store.values(Scope.DEFAULT, null, kind, name);
        List<Resolved> resolved = new ArrayList<>();
        for (Param param : declared) {
            if (param.secret()) {
                continue;
            }
            Json byAdmin = valid(param, defaults.get(param.key()));
            resolved.add(byAdmin != null ? new Resolved(param, byAdmin, Source.ADMIN)
                    : new Resolved(param, param.byDefault(), Source.BUILTIN));
        }
        return List.copyOf(resolved);
    }

    /** 没锁定时。 */
    public Params params(Principal user, Kind kind, String name, List<Param> declared) {
        return params(user, kind, name, declared, false);
    }

    /** 给插件用的一包值。没有非密码参数时是 {@link Params#NONE}。 */
    public Params params(Principal user, Kind kind, String name, List<Param> declared, boolean locked) {
        List<Resolved> resolved = resolve(user, kind, name, declared, locked);
        if (resolved.isEmpty()) {
            return Params.NONE;
        }
        Map<String, Json> values = new LinkedHashMap<>();
        resolved.forEach(item -> values.put(item.param().key(), item.value()));
        return new Params(new Json.Obj(values));
    }

    /** 只能取到声明过的密码键。 */
    public Secrets secrets(Principal user, Kind kind, String name, List<Param> declared) {
        List<String> keys = declared.stream().filter(Param::secret).map(Param::key).toList();
        if (keys.isEmpty()) {
            return Secrets.NONE;
        }
        return key -> keys.contains(key) ? store.secret(user, kind, name, key) : Optional.empty();
    }

    private static Json valid(Param param, Json stored) {
        if (stored == null) {
            return null;
        }
        try {
            return ParamRules.check(param, stored);
        } catch (InvalidParam outdated) {
            return null;
        }
    }
}
