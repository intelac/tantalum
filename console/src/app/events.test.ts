import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { ConversationStore } from "../store/ConversationStore";
import { handleEvent } from "./events";

describe("handleEvent", () => {
  it("applies items, accumulates chunks and clears them when the model call settles", () => {
    const store = new ConversationStore();
    const queries = new QueryClient();
    const invalidate = vi.spyOn(queries, "invalidateQueries").mockResolvedValue();

    handleEvent({ id: "0.1", event: "item", data: '{"kind":"user","id":"0.0","session":"s","run":"run-0","at":"2026-10-31T00:00:00Z","text":"hi","queued":false}' }, store, queries);
    expect(store.getOrder("s")).toEqual(["0.0"]);
    expect(invalidate).toHaveBeenCalledTimes(1);

    handleEvent({ event: "chunk", data: '{"run":"run-0","session":"s","kind":"text","text":"Hel"}' }, store, queries);
    handleEvent({ event: "chunk", data: '{"run":"run-0","session":"s","kind":"reasoning","text":"think"}' }, store, queries);
    expect(store.getLive("run-0")).toEqual({ text: "Hel", reasoning: "think" });

    handleEvent({ id: "2.0", event: "PhaseChanged", data: '{"run":"run-0","session":"s","phase":"AwaitingTools"}' }, store, queries);
    expect(store.getLive("run-0")).toEqual({ text: "", reasoning: "" });
    expect(invalidate).toHaveBeenCalledTimes(2);

    handleEvent({ event: "chunk", data: '{"run":"run-0","session":"s","kind":"text","text":"x"}' }, store, queries);
    handleEvent({ id: "3.0", event: "PhaseChanged", data: '{"run":"run-0","session":"s","phase":"AwaitingModel"}' }, store, queries);
    expect(store.getLive("run-0").text).toBe("x");
  });

  it("ignores malformed data and unknown events", () => {
    const store = new ConversationStore();
    const queries = new QueryClient();
    handleEvent({ event: "item", data: "{oops" }, store, queries);
    handleEvent({ event: "HookReported", data: "{}" }, store, queries);
    expect(store.getOrder("s")).toEqual([]);
  });

  it("drops payloads of the wrong shape instead of throwing", () => {
    const store = new ConversationStore();
    const queries = new QueryClient();
    handleEvent({ event: "item", data: "null" }, store, queries);
    handleEvent({ event: "item", data: '{"kind":"user"}' }, store, queries);
    handleEvent({ event: "chunk", data: '{"run":"run-0","kind":"video","text":"x"}' }, store, queries);
    handleEvent({ event: "chunk", data: '{"run":"run-0","kind":"text","text":5}' }, store, queries);
    handleEvent({ event: "PhaseChanged", data: '{"run":1}' }, store, queries);
    expect(store.getOrder("s")).toEqual([]);
    expect(store.getLive("run-0")).toEqual({ text: "", reasoning: "" });
  });
});
