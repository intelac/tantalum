import { describe, expect, it } from "vitest";
import { pick } from "./localText";

// 需求 4.5：只给一个字符串的照原样；带语言的取用户的语言，没有就用英文。
describe("pick", () => {
  it("a plain string is shown as written", () => {
    expect(pick("每天的上限", "en")).toBe("每天的上限");
  });

  it("picks the user's language, else English", () => {
    const text = { en: "Daily limit", zh: "每天的上限" };
    expect(pick(text, "zh")).toBe("每天的上限");
    expect(pick(text, "ja")).toBe("Daily limit");
    expect(pick(text, "en")).toBe("Daily limit");
  });
});
