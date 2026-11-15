import { describe, expect, it } from "vitest";
import { apiQuery, DEFAULT_FILTERS, parseFilters, serializeFilters, windowStart } from "./filters";

describe("filters", () => {
  it("defaults are omitted, so /runs means the last 7 days newest first", () => {
    expect(parseFilters("")).toEqual(DEFAULT_FILTERS);
    expect(serializeFilters(DEFAULT_FILTERS)).toBe("");
  });

  it("round-trips every field and keeps the end order fixed", () => {
    const query = "window=24h&end=FAILED,RUNNING&toolErrors=true&tool=list_dir&model=local%2Fqwen&sort=ms";
    const filters = parseFilters(query);
    expect(filters).toEqual({ window: "24h", ends: ["FAILED", "RUNNING"], toolErrors: true, tool: "list_dir", model: "local/qwen", sort: "ms" });
    expect(parseFilters(serializeFilters(filters))).toEqual(filters);
    expect(parseFilters("end=running,failed").ends).toEqual(["FAILED", "RUNNING"]);
  });

  it("drops values it does not know", () => {
    expect(parseFilters("window=1y&sort=fastest&end=EXPLODED&toolErrors=yes")).toEqual(DEFAULT_FILTERS);
  });

  it("turns the window into a from time rounded to the minute, with the zone only for the summary", () => {
    const now = Date.parse("2026-11-15T12:34:56.789Z");
    expect(windowStart("24h", now)?.toISOString()).toBe("2026-11-14T12:34:00.000Z");
    expect(windowStart("all", now)).toBeUndefined();
    const filters = parseFilters("sort=tokens&tool=bash");
    expect(apiQuery(filters, now, false)).toBe("from=2026-11-08T12%3A34%3A00.000Z&tool=bash&sort=tokens");
    expect(apiQuery(filters, now, true, "Asia/Tokyo")).toBe("from=2026-11-08T12%3A34%3A00.000Z&tool=bash&tz=Asia%2FTokyo");
    expect(apiQuery(parseFilters("window=all"), now, false)).toBe("");
  });
});
