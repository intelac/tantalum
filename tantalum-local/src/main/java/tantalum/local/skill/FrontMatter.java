package tantalum.local.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code SKILL.md} 开头两行 {@code ---} 之间的属性（照 pi {@code src/utils/frontmatter.ts:11-38} 的切法）。连接程序里没有 YAML 库，
 * 这里只认技能用得到的那部分：顶层的 {@code key: value}，值是普通文字、单双引号、{@code true}/{@code false}、{@code >} 和 {@code |}
 * 开头的多行文字、缩进的续行。缩进的子结构（列表、嵌套的表）整块跳过，值记成 {@link #OTHER}。
 */
final class FrontMatter {

    /** 不是文字也不是布尔的值（列表、嵌套的表、空）。 */
    static final Object OTHER = new Object() {
        @Override
        public String toString() {
            return "(structure)";
        }
    };

    private static final Pattern KEY = Pattern.compile("^([A-Za-z0-9_-]+):(?:[ \\t]+(.*))?$");
    private static final Pattern BLOCK = Pattern.compile("^([|>])[-+0-9]*[ \\t]*(?:#.*)?$");

    private FrontMatter() {}

    /** 属性和正文。没有属性块时属性为空、正文是全文。 */
    record Parsed(Map<String, Object> fields, String body) {}

    /** 属性块写坏了：开了没关、顶层一行不是 {@code key: value}、引号没关。 */
    static final class Invalid extends Exception {

        Invalid(String message) {
            super(message);
        }
    }

    static Parsed parse(String raw) throws Invalid {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (!text.startsWith("---") || !firstLine(text).stripTrailing().equals("---")) {
            return new Parsed(Map.of(), text);
        }
        List<String> lines = List.of(text.split("\n", -1));
        int close = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).stripTrailing().equals("---")) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            throw new Invalid("the --- block is not closed");
        }
        String body = String.join("\n", lines.subList(close + 1, lines.size()));
        return new Parsed(fields(lines.subList(1, close)), body);
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    private static Map<String, Object> fields(List<String> lines) throws Invalid {
        Map<String, Object> fields = new LinkedHashMap<>();
        int at = 0;
        while (at < lines.size()) {
            String line = lines.get(at);
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                at++;
                continue;
            }
            Matcher key = KEY.matcher(line.stripTrailing());
            if (!key.matches()) {
                throw new Invalid("line " + (at + 2) + " is not \"key: value\"");
            }
            // 这个键下面缩进的行：多行文字的内容、续行，或子结构
            int end = at + 1;
            while (end < lines.size() && (lines.get(end).isBlank() || indented(lines.get(end)))) {
                end++;
            }
            List<String> nested = new ArrayList<>(lines.subList(at + 1, end));
            while (!nested.isEmpty() && nested.getLast().isBlank()) {
                nested.removeLast();
            }
            String rest = key.group(2) == null ? "" : key.group(2).strip();
            fields.put(key.group(1), value(rest, nested, at + 2));
            at = end;
        }
        return fields;
    }

    private static boolean indented(String line) {
        return line.startsWith(" ") || line.startsWith("\t");
    }

    private static Object value(String rest, List<String> nested, int lineNumber) throws Invalid {
        Matcher block = BLOCK.matcher(rest);
        if (block.matches()) {
            return block(nested, block.group(1).equals(">"));
        }
        if (rest.isEmpty()) {
            if (nested.isEmpty()) {
                return OTHER;
            }
            String first = nested.getFirst().strip();
            if (first.startsWith("- ") || first.equals("-") || KEY.matcher(first).matches()) {
                return OTHER;
            }
            return scalar(folded(nested));
        }
        if (rest.startsWith("\"") || rest.startsWith("'")) {
            return quoted(rest + (nested.isEmpty() ? "" : " " + folded(nested)), lineNumber);
        }
        if (rest.startsWith("[") || rest.startsWith("{")) {
            return OTHER;
        }
        String plain = uncommented(rest);
        return scalar(nested.isEmpty() ? plain : plain + " " + folded(nested));
    }

    /** 普通文字里只有 {@code true}、{@code false} 是布尔（YAML 1.2，同 pi 用的解析器）；别的都当文字。 */
    private static Object scalar(String text) {
        return switch (text) {
            case "true", "True", "TRUE" -> Boolean.TRUE;
            case "false", "False", "FALSE" -> Boolean.FALSE;
            default -> text;
        };
    }

    /** 续行折成一行：每行去掉首尾空白，用空格连起来。 */
    private static String folded(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String piece = line.strip();
            if (piece.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(piece);
        }
        return out.toString();
    }

    /** {@code |} 保留换行；{@code >} 把相邻的行折成一行，空行变成换行。末尾的空白去掉。 */
    private static String block(List<String> lines, boolean fold) {
        int indent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (!line.isBlank()) {
                indent = Math.min(indent, line.length() - line.stripLeading().length());
            }
        }
        StringBuilder out = new StringBuilder();
        boolean breakBefore = false;
        for (String line : lines) {
            String piece = line.isBlank() ? "" : line.substring(indent).stripTrailing();
            if (!fold) {
                out.append(piece).append('\n');
            } else if (piece.isEmpty()) {
                breakBefore = true;
            } else {
                if (!out.isEmpty()) {
                    out.append(breakBefore ? '\n' : ' ');
                }
                out.append(piece);
                breakBefore = false;
            }
        }
        return out.toString().stripTrailing();
    }

    private static String uncommented(String text) {
        int hash = text.indexOf(" #");
        return hash < 0 ? text : text.substring(0, hash).stripTrailing();
    }

    private static String quoted(String text, int lineNumber) throws Invalid {
        char quote = text.charAt(0);
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == '\'' && c == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                    continue;
                }
                return closed(out, text.substring(i + 1), lineNumber);
            }
            if (quote == '"' && c == '"') {
                return closed(out, text.substring(i + 1), lineNumber);
            }
            if (quote == '"' && c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> next;
                });
                continue;
            }
            out.append(c);
        }
        throw new Invalid("the quote on line " + lineNumber + " is not closed");
    }

    /** 引号关上之后只能是空白或注释。 */
    private static String closed(StringBuilder value, String after, int lineNumber) throws Invalid {
        String tail = after.strip();
        if (!tail.isEmpty() && !tail.startsWith("#")) {
            throw new Invalid("unexpected text after the quote on line " + lineNumber);
        }
        return value.toString();
    }
}
