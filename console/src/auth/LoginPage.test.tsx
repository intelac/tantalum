import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LoginPage } from "./LoginPage";

function reply(status: number, body: string) {
  return { ok: status >= 200 && status < 300, status, text: () => Promise.resolve(body) };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("LoginPage", () => {
  it("logs in and hands the user back", async () => {
    const fetchMock = vi.fn().mockResolvedValue(reply(200, '{"username":"alice"}'));
    vi.stubGlobal("fetch", fetchMock);
    const onLoggedIn = vi.fn();
    render(<LoginPage onLoggedIn={onLoggedIn} />);

    await userEvent.type(screen.getByLabelText("用户名"), " alice ");
    await userEvent.type(screen.getByLabelText("密码"), "secret");
    await userEvent.click(screen.getByRole("button", { name: "登录" }));

    expect(onLoggedIn).toHaveBeenCalledWith({ username: "alice" });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/auth/login");
    expect(JSON.parse(init.body as string)).toEqual({ username: "alice", password: "secret" });
  });

  it("says only that the username or password is wrong", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(401, '{"error":"no such user"}')));
    render(<LoginPage onLoggedIn={vi.fn()} />);
    await userEvent.type(screen.getByLabelText("用户名"), "nobody");
    await userEvent.type(screen.getByLabelText("密码"), "x");
    await userEvent.click(screen.getByRole("button", { name: "登录" }));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toBe("用户名或密码错误");
    expect(alert.textContent).not.toContain("user");
  });

  it("explains throttling and outages differently", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(429, "")));
    render(<LoginPage onLoggedIn={vi.fn()} />);
    await userEvent.type(screen.getByLabelText("用户名"), "a");
    await userEvent.type(screen.getByLabelText("密码"), "b");
    await userEvent.click(screen.getByRole("button", { name: "登录" }));
    expect((await screen.findByRole("alert")).textContent).toBe("尝试次数过多，请稍后再试");

    cleanup();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    render(<LoginPage onLoggedIn={vi.fn()} />);
    await userEvent.type(screen.getByLabelText("用户名"), "a");
    await userEvent.type(screen.getByLabelText("密码"), "b");
    await userEvent.click(screen.getByRole("button", { name: "登录" }));
    expect((await screen.findByRole("alert")).textContent).toBe("无法连接服务，请稍后再试");
  });
});
