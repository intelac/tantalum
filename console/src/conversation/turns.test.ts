import { describe, expect, it } from "vitest";
import { PREVIEW, currentTurn, turnLabel } from "./turns";

// session-find-pin R4.2、R4.4
describe("turnLabel", () => {
  it("takes the first non-empty line, cut at the preview length by characters", () => {
    expect(turnLabel("\n  why is total negative?\nmore", "file")).toBe("why is total negative?");
    expect(turnLabel("😀".repeat(PREVIEW + 5), "file")).toBe(`${"😀".repeat(PREVIEW)}…`);
    expect(turnLabel("x".repeat(PREVIEW), "file")).toBe("x".repeat(PREVIEW));
  });

  it("uses the fallback for an attachment-only turn", () => {
    expect(turnLabel(" \n ", "file")).toBe("file");
  });
});

describe("currentTurn", () => {
  it("is the last turn whose top passed the reading line", () => {
    expect(currentTurn([-500, -20, 150, 900], 200)).toBe(2);
    expect(currentTurn([300, 900], 200)).toBe(0);
    expect(currentTurn([], 200)).toBe(-1);
  });
});
