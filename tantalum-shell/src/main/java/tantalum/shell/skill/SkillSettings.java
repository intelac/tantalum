package tantalum.shell.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tantalum.shell.local.LocalDirs;
import tantalum.shell.param.ParamStore;
import tantalum.shell.param.ParamStore.Scope;
import tantalum.shell.plugin.Kind;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

/**
 * 每个用户自己填的技能目录、wiki 目录，和关掉的技能。存在 {@link ParamStore}（自己那份、本机这一组，名字 {@code skills}、{@code wiki}）。
 * 目录只查写法，不查存不存在：它在用户的电脑上，在不在允许的范围里由连接程序说。
 */
public final class SkillSettings implements LocalDirs {

    public static final String SKILLS = "skills";
    public static final String WIKI = "wiki";
    public static final int MAX_DIR = 1000;
    static final String DIR = "dir";
    static final String OFF = "off";
    /** 绝对路径：{@code /…}、{@code ~}、{@code ~/…}、{@code C:\…}、{@code \\server\…}。 */
    private static final Pattern ABSOLUTE = Pattern.compile("^(/|~$|~[/\\\\]|[A-Za-z]:[/\\\\]|\\\\\\\\).*");
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,64}");

    private final ParamStore params;

    public SkillSettings(ParamStore params) {
        this.params = params;
    }

    @Override
    public Optional<String> skillsDir(Principal user) {
        return dir(user, SKILLS);
    }

    @Override
    public Optional<String> wikiDir(Principal user) {
        return dir(user, WIKI);
    }

    @Override
    public Set<String> off(Principal user) {
        Set<String> names = new TreeSet<>();
        if (values(user, SKILLS).get(OFF) instanceof Json.Arr list) {
            for (Json item : list.items()) {
                if (item instanceof Json.Str name) {
                    names.add(name.value());
                }
            }
        }
        return names;
    }

    /** 空串（或只有空白）是清掉。写法不对抛 {@link IllegalArgumentException}，消息是给界面的代码。 */
    public void setSkillsDir(Principal user, String dir) {
        setDir(user, SKILLS, dir);
    }

    public void setWikiDir(Principal user, String dir) {
        setDir(user, WIKI, dir);
    }

    /** 关掉或重新打开一个技能。名字只查写法：技能可能暂时不在目录里。 */
    public void setOff(Principal user, String name, boolean off) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name-invalid");
        }
        Set<String> names = off(user);
        if (off ? !names.add(name) : !names.remove(name)) {
            return;
        }
        if (names.isEmpty()) {
            params.save(Scope.OWN, user, Kind.LOCAL, SKILLS, Map.of(), Set.of(OFF));
            return;
        }
        List<Json> items = new ArrayList<>();
        names.forEach(each -> items.add(new Json.Str(each)));
        params.save(Scope.OWN, user, Kind.LOCAL, SKILLS, Map.of(OFF, new Json.Arr(items)), Set.of());
    }

    private Optional<String> dir(Principal user, String name) {
        return values(user, name).get(DIR) instanceof Json.Str dir && !dir.value().isEmpty() ? Optional.of(dir.value()) : Optional.empty();
    }

    private Map<String, Json> values(Principal user, String name) {
        return params.values(Scope.OWN, user, Kind.LOCAL, name);
    }

    private void setDir(Principal user, String name, String dir) {
        String trimmed = dir == null ? "" : dir.strip();
        if (trimmed.isEmpty()) {
            params.save(Scope.OWN, user, Kind.LOCAL, name, Map.of(), Set.of(DIR));
            return;
        }
        params.save(Scope.OWN, user, Kind.LOCAL, name, Map.of(DIR, new Json.Str(checked(trimmed))), Set.of());
    }

    static String checked(String dir) {
        if (dir.length() > MAX_DIR) {
            throw new IllegalArgumentException("dir-too-long");
        }
        if (dir.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("dir-invalid");
        }
        if (!ABSOLUTE.matcher(dir).matches()) {
            throw new IllegalArgumentException("dir-not-absolute");
        }
        return dir;
    }
}
