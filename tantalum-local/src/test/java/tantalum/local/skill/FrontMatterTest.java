package tantalum.local.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FrontMatterTest {

    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of("plain", "description: Extract text from PDFs", "Extract text from PDFs"),
                Arguments.of("colon inside", "description: Use when: the user asks", "Use when: the user asks"),
                Arguments.of("double quoted", "description: \"a \\\"b\\\" # not a comment\"", "a \"b\" # not a comment"),
                Arguments.of("single quoted", "description: 'it''s fine'", "it's fine"),
                Arguments.of("comment after value", "description: text # note", "text"),
                Arguments.of("comment after quote", "description: \"text\" # note", "text"),
                Arguments.of("true", "description: true", Boolean.TRUE),
                Arguments.of("false", "description: false", Boolean.FALSE),
                Arguments.of("quoted true stays text", "description: \"true\"", "true"),
                Arguments.of("yes is text", "description: yes", "yes"),
                Arguments.of("number is text", "description: 42", "42"),
                Arguments.of("folded block", "description: >\n  first\n  second\n\n  third", "first second\nthird"),
                Arguments.of("folded strip", "description: >-\n  first\n  second", "first second"),
                Arguments.of("literal block", "description: |\n  first\n    second", "first\n  second"),
                Arguments.of("continued plain", "description: first\n  second", "first second"),
                Arguments.of("plain on the next line", "description:\n  first\n  second", "first second"),
                Arguments.of("continued quote", "description: \"first\n  second\"", "first second"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    void readsAValue(String label, String yaml, Object expected) throws Exception {
        assertEquals(expected, FrontMatter.parse("---\n" + yaml + "\n---\nbody").fields().get("description"));
    }

    @Test
    void splitsFieldsAndBody() throws Exception {
        FrontMatter.Parsed parsed = FrontMatter.parse("---\nname: pdf-tools\ndescription: Does things\n---\n# Title\n\nText\n");

        assertEquals(Map.of("name", "pdf-tools", "description", "Does things"), parsed.fields());
        assertEquals("# Title\n\nText\n", parsed.body());
    }

    @Test
    void withoutABlockEverythingIsBody() throws Exception {
        FrontMatter.Parsed parsed = FrontMatter.parse("# Title\n---\nnot: yaml\n---\n");

        assertTrue(parsed.fields().isEmpty());
        assertEquals("# Title\n---\nnot: yaml\n---\n", parsed.body());
    }

    @Test
    void windowsLineEndingsAndAByteOrderMarkAreAccepted() throws Exception {
        FrontMatter.Parsed parsed = FrontMatter.parse("﻿---\r\nname: a\r\ndescription: b\r\n---\r\nbody\r\n");

        assertEquals(Map.of("name", "a", "description", "b"), parsed.fields());
        assertEquals("body\n", parsed.body());
    }

    @Test
    void nestedStructuresAreSkippedNotRead() throws Exception {
        FrontMatter.Parsed parsed = FrontMatter.parse("""
                ---
                name: a
                metadata:
                  author: someone
                  tags:
                    - x
                allowed-tools:
                  - read
                inline: [a, b]
                empty:
                # a comment

                description: after
                ---
                """);

        assertEquals("a", parsed.fields().get("name"));
        assertEquals("after", parsed.fields().get("description"));
        assertSame(FrontMatter.OTHER, parsed.fields().get("metadata"));
        assertSame(FrontMatter.OTHER, parsed.fields().get("allowed-tools"));
        assertSame(FrontMatter.OTHER, parsed.fields().get("inline"));
        assertSame(FrontMatter.OTHER, parsed.fields().get("empty"));
    }

    static Stream<Arguments> broken() {
        return Stream.of(
                Arguments.of("not closed", "---\nname: a\n"),
                Arguments.of("a line that is not key: value", "---\nname: a\njust words\n---\n"),
                Arguments.of("quote not closed", "---\nname: \"a\n---\n"),
                Arguments.of("text after the quote", "---\nname: \"a\" b\n---\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("broken")
    void rejectsABrokenBlock(String label, String text) {
        assertThrows(FrontMatter.Invalid.class, () -> FrontMatter.parse(text));
    }
}
