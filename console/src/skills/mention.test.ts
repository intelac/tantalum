import { describe, expect, it } from "vitest";
import type { MenuSkill } from "../api/types";
import { applyMention, filterSkills, mentionAt } from "./mention";

const skill = (name: string): MenuSkill => ({ name, description: name, manualOnly: false });

/** skills-wiki 任务 22（需求 5.1）：光标处算不算在点名、怎么筛选、填进去是什么样。 */
describe("mentionAt", () => {
  it.each([
    ["/", 1, { start: 0, query: "" }],
    ["/pdf", 4, { start: 0, query: "pdf" }],
    ["use /pdf-t", 10, { start: 4, query: "pdf-t" }],
    ["line one\n/de", 12, { start: 9, query: "de" }],
    ["a\t/x", 4, { start: 2, query: "x" }],
    ["/pdf tools", 4, { start: 0, query: "pdf" }],
  ] as const)("finds a mention in %j at %i", (text, caret, expected) => {
    expect(mentionAt(text, caret)).toEqual(expected);
  });

  it.each([
    ["src/main", 8],
    ["https://example.com/pdf", 23],
    ["1/2", 3],
    ["/PDF", 4],
    ["/pdf tools", 10],
    ["/pdf", 2],
    ["/usr/local", 4],
    ["", 0],
    ["plain words", 5],
    ["/pdf_tools", 10],
  ] as const)("finds none in %j at %i", (text, caret) => {
    expect(mentionAt(text, caret)).toBeNull();
  });
});

describe("filterSkills", () => {
  const all = [skill("deploy"), skill("pdf-tools"), skill("release-pdf"), skill("standup")];
  it("keeps everything for an empty query and puts prefix matches first", () => {
    expect(filterSkills(all, "").map((item) => item.name)).toEqual(["deploy", "pdf-tools", "release-pdf", "standup"]);
    expect(filterSkills(all, "pdf").map((item) => item.name)).toEqual(["pdf-tools", "release-pdf"]);
    expect(filterSkills(all, "d").map((item) => item.name)).toEqual(["deploy", "pdf-tools", "release-pdf", "standup"]);
    expect(filterSkills(all, "zzz")).toEqual([]);
  });
});

describe("applyMention", () => {
  it("replaces what was typed with the name and one space, leaving the rest alone", () => {
    expect(applyMention("/pd", { start: 0, query: "pd" }, 3, "pdf-tools")).toEqual({ text: "/pdf-tools ", caret: 11 });
    expect(applyMention("use /pd now", { start: 4, query: "pd" }, 7, "pdf-tools")).toEqual({ text: "use /pdf-tools now", caret: 15 });
    expect(applyMention("a\n/", { start: 2, query: "" }, 3, "deploy")).toEqual({ text: "a\n/deploy ", caret: 10 });
  });
});
