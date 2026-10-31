import { beforeEach, describe, expect, it } from "vitest";
import { connectionState, reportConnection, reportHalted, reportUnauthorized, resetConnection } from "./connection";

beforeEach(resetConnection);

describe("connection", () => {
  it("only reports a loss after it was once connected, and counts reconnects", () => {
    reportConnection(false);
    expect(connectionState().lost).toBe(false);
    reportConnection(true);
    expect(connectionState()).toMatchObject({ connected: true, lost: false, reconnects: 1 });
    reportConnection(false);
    expect(connectionState()).toMatchObject({ connected: false, lost: true, reconnects: 1 });
    reportConnection(true);
    expect(connectionState()).toMatchObject({ connected: true, lost: false, reconnects: 2 });
    reportConnection(true);
    expect(connectionState().reconnects).toBe(2);
  });

  it("remembers 503 and 401 until reset", () => {
    reportHalted();
    reportUnauthorized();
    expect(connectionState()).toMatchObject({ halted: true, unauthorized: true, connected: false });
    resetConnection();
    expect(connectionState()).toMatchObject({ halted: false, unauthorized: false });
  });
});
