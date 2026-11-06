package tantalum.shell.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import tantalum.shell.model.OpenAiThinking;

/**
 * 一个模型的思考档位和默认档位（model-picker M5）。档位名只是标识，画面上的中文名在控制台的 {@code zh.ts}。
 * 没有档位时 {@code names} 为空、{@code defaultLevel} 为 null，画面上不显示档位那一栏。
 */
public record Levels(List<String> names, String defaultLevel) {

    /** Claude 的 {@code output_config.effort}，按这个顺序（Claude 文档 SKILL“Thinking &amp; Effort”）。 */
    public static final List<String> CLAUDE = List.of("low", "medium", "high", "xhigh", "max");
    /** 没有档位。 */
    public static final Levels NONE = new Levels(List.of(), null);
    private static final String MIDDLE = "medium";

    public Levels {
        names = List.copyOf(names);
        if (defaultLevel != null && !names.contains(defaultLevel)) {
            throw new IllegalArgumentException("default level " + defaultLevel + " is not one of " + names);
        }
        if (defaultLevel == null && !names.isEmpty()) {
            throw new IllegalArgumentException("levels " + names + " need a default");
        }
    }

    public boolean isEmpty() {
        return names.isEmpty();
    }

    /**
     * Claude 模型的档位：五档里 Models 接口说支持的（{@code capabilities.effort.<档位>.supported}），按顺序。默认档位：路线的
     * {@code preferred} 在里面就用它，否则取离 {@code medium} 最近的，一样近取低的。不提供“关”（M5）。
     */
    public static Levels claude(Collection<String> supported, String preferred) {
        List<String> names = CLAUDE.stream().filter(supported::contains).toList();
        if (names.isEmpty()) {
            return NONE;
        }
        if (names.contains(preferred)) {
            return new Levels(names, preferred);
        }
        int middle = CLAUDE.indexOf(MIDDLE);
        String nearest = names.getFirst();
        for (String name : names) {
            int distance = Math.abs(CLAUDE.indexOf(name) - middle);
            int best = Math.abs(CLAUDE.indexOf(nearest) - middle);
            // names 从低到高，所以一样近时留着前面那个低的
            if (distance < best) {
                nearest = name;
            }
        }
        return new Levels(names, nearest);
    }

    /**
     * OpenAI 兼容模型的档位，由管理员写的思考写法定（M5）：{@code none} 没有档位；{@code reasoning-effort} 用 {@code efforts}
     * （没写是 low、medium、high），默认 {@code preferred}（没写是 medium，没有 medium 时是第一档）；三种开关是 off、on，默认 on。
     * 档位为空、默认档位不在档位里都抛出，消息写明可以写什么。
     */
    public static Levels openAi(OpenAiThinking thinking, List<String> efforts, String preferred) {
        List<String> names = switch (thinking) {
            case NONE -> List.of();
            case REASONING_EFFORT -> efforts == null ? List.of("low", MIDDLE, "high") : cleaned(efforts);
            default -> OpenAiThinking.TOGGLE;
        };
        if (thinking == OpenAiThinking.NONE) {
            return NONE;
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("thinking=" + thinking.configName() + " needs at least one effort");
        }
        String fallback = thinking == OpenAiThinking.REASONING_EFFORT ? (names.contains(MIDDLE) ? MIDDLE : names.getFirst()) : OpenAiThinking.ON;
        String chosen = preferred == null || preferred.isBlank() ? fallback : preferred.strip();
        if (!names.contains(chosen)) {
            throw new IllegalArgumentException("default-effort=" + chosen + " is not one of " + String.join(", ", names));
        }
        return new Levels(names, chosen);
    }

    private static List<String> cleaned(List<String> efforts) {
        List<String> names = new ArrayList<>();
        for (String effort : efforts) {
            String name = effort == null ? "" : effort.strip();
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }
}
