import { describe, expect, it } from "vitest";
import { currentRoute, loginPath, navigate, parseRoute, sessionPath, tracePath } from "./router";

describe("parseRoute", () => {
  it("resolves the three pages and home", () => {
    expect(parseRoute("/")).toEqual({ page: "home" });
    expect(parseRoute("/login")).toEqual({ page: "login" });
    expect(parseRoute("/login", "?next=%2Fs%2Fabc")).toEqual({ page: "login", next: "/s/abc" });
    expect(parseRoute("/s/abc")).toEqual({ page: "session", session: "abc" });
    expect(parseRoute("/s/abc/trace/run-12")).toEqual({ page: "trace", session: "abc", run: "run-12" });
    expect(parseRoute("/s/abc/trace")).toEqual({ page: "trace", session: "abc" });
    expect(tracePath("abc")).toBe("/s/abc/trace");
  });

  it("decodes ids and rejects bad paths", () => {
    expect(parseRoute("/s/a%20b/")).toEqual({ page: "session", session: "a b" });
    expect(parseRoute("/s/")).toEqual({ page: "notFound", path: "/s/" });
    expect(parseRoute("/s/abc/other")).toEqual({ page: "notFound", path: "/s/abc/other" });
    expect(parseRoute("/s/%E0%A4%A/trace/x").page).toBe("notFound");
    expect(parseRoute("/login", "?next=https%3A%2F%2Fevil")).toEqual({ page: "login" });
    // 协议相对地址会被浏览器当成别的主机，不能当作站内路径。
    expect(parseRoute("/login", "?next=%2F%2Fevil.example")).toEqual({ page: "login" });
    expect(parseRoute("/login", "?next=%2F%5Cevil.example")).toEqual({ page: "login" });
  });

  it("builds paths that parse back", () => {
    expect(parseRoute(sessionPath("a b"))).toEqual({ page: "session", session: "a b" });
    expect(parseRoute(tracePath("s/1", "run-3"))).toEqual({ page: "trace", session: "s/1", run: "run-3" });
    expect(loginPath("/s/abc")).toBe("/login?next=%2Fs%2Fabc");
    expect(loginPath("/")).toBe("/login");
    expect(loginPath()).toBe("/login");
  });
});

describe("navigate", () => {
  it("updates history and the current route, and reacts to popstate", () => {
    navigate(sessionPath("one"));
    expect(window.location.pathname).toBe("/s/one");
    expect(currentRoute()).toEqual({ page: "session", session: "one" });
    navigate(tracePath("one", "run-1"), true);
    expect(currentRoute()).toEqual({ page: "trace", session: "one", run: "run-1" });

    window.history.pushState(null, "", "/login");
    window.dispatchEvent(new PopStateEvent("popstate"));
    expect(currentRoute()).toEqual({ page: "login" });
  });
});
