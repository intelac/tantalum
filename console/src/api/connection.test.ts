import { beforeEach, describe, expect, it } from "vitest";
import { connectionState, reportConnection, reportHalted, resetConnection } from "./connection";

beforeEach(resetConnection);

describe("connection", () => {
  it("only reports a loss after it was once connected, and counts connects", () => {
    reportConnection(false);
    expect(connectionState().lost).toBe(false);
    reportConnection(true);
    expect(connectionState()).toMatchObject({ connected: true, lost: false, connects: 1 });
    reportConnection(false);
    expect(connectionState()).toMatchObject({ connected: false, lost: true, connects: 1 });
    reportConnection(true);
    expect(connectionState()).toMatchObject({ connected: true, lost: false, connects: 2 });
    reportConnection(true);
    expect(connectionState().connects).toBe(2);
  });

  it("remembers 503 until reset", () => {
    reportHalted();
    expect(connectionState().halted).toBe(true);
    resetConnection();
    expect(connectionState().halted).toBe(false);
  });
});
