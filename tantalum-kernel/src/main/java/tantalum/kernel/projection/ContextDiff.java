package tantalum.kernel.projection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tantalum.value.state.ContextSection;

/** 比较两组分段，只给出变化（DSH agent-instructions：内容未变不重发，只注入变更和移除记录）。没有变化时返回空串。 */
public final class ContextDiff {

    private ContextDiff() {}

    public static String of(List<ContextSection> before, List<ContextSection> after) {
        Map<String, ContextSection> old = before.stream().collect(Collectors.toMap(ContextSection::name, Function.identity()));
        List<String> parts = new ArrayList<>();
        for (ContextSection section : after) {
            ContextSection previous = old.get(section.name());
            if (previous == null || !previous.body().equals(section.body())) {
                parts.add(section.byLine() && previous != null ? lineChanges(section, previous) : section.tagged());
            }
        }
        Set<String> names = after.stream().map(ContextSection::name).collect(Collectors.toSet());
        for (ContextSection section : before) {
            if (!names.contains(section.name())) {
                parts.add(section.name() + " no longer applies; ignore what you were given earlier.");
            }
        }
        return String.join("\n\n", parts);
    }

    private static String lineChanges(ContextSection now, ContextSection previous) {
        Set<String> oldLines = lines(previous.body());
        Set<String> newLines = lines(now.body());
        List<String> added = newLines.stream().filter(line -> !oldLines.contains(line)).toList();
        List<String> removed = oldLines.stream().filter(line -> !newLines.contains(line)).toList();
        StringBuilder text = new StringBuilder();
        if (!added.isEmpty()) {
            text.append("Added:\n").append(String.join("\n", added));
        }
        if (!removed.isEmpty()) {
            text.append(text.isEmpty() ? "" : "\n").append("Removed:\n").append(String.join("\n", removed));
        }
        return new ContextSection(now.name(), text.toString(), true).tagged();
    }

    private static Set<String> lines(String body) {
        return body.lines().filter(line -> !line.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

}
