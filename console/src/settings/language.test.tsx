import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { User } from "../api/types";
import { useCurrentUser } from "../auth/useCurrentUser";
import { currentLanguage } from "../text";
import { LANGUAGE_KEY } from "../text/language";
import { SettingsDialog } from "./SettingsDialog";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
});

/** 假后端：`/api/auth/me` 回 `user`（null 时 401），`PUT /api/settings/language` 回 `putStatus` 并记下请求体。 */
function fakeBackend(user: User | null, putStatus: number | "never" = 200) {
  const puts: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((path: string, init?: RequestInit) => {
      const reply = (status: number, body: unknown) =>
        Promise.resolve({ ok: status < 300, status, text: () => Promise.resolve(JSON.stringify(body)) });
      if (path === "/api/auth/me") return user === null ? reply(401, { error: "not_authenticated", code: "auth/not-authenticated" }) : reply(200, user);
      if (path === "/api/settings/language") {
        puts.push(init?.body as string);
        if (putStatus === "never") return new Promise(() => undefined);
        return putStatus === 200 ? reply(200, { compaction: { percent: 100, points: [] }, language: null }) : reply(putStatus, {});
      }
      if (path === "/api/settings") return reply(200, { compaction: { percent: 100, points: [] }, language: null });
      return reply(404, {});
    }),
  );
  return puts;
}

/** 设置窗口旁边放一个输入框：切换语言后窗口还开着、草稿还在（I4：不刷新、不重新挂载）。 */
function Page() {
  const { user } = useCurrentUser();
  const [draft, setDraft] = useState("");
  return (
    <>
      <textarea aria-label="draft" value={draft} onChange={(event) => setDraft(event.target.value)} />
      {user !== null && <SettingsDialog open onClose={() => undefined} config={undefined} username={user.username} onLogout={() => undefined} />}
    </>
  );
}

function mount() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <Page />
    </QueryClientProvider>,
  );
}

async function openLanguagePane() {
  await userEvent.click(await screen.findByRole("button", { name: "语言" }));
}

describe("language pane", () => {
  // 需求 1.3、I4
  it("switches on the spot, keeps the dialog and the draft, then saves", async () => {
    const puts = fakeBackend({ username: "yi", language: null });
    mount();
    await userEvent.type(screen.getByRole("textbox", { name: "draft" }), "还没发出去的话");
    await openLanguagePane();
    await userEvent.click(screen.getByRole("radio", { name: "English" }));
    expect(screen.getByRole("dialog", { name: "Settings" })).toBeTruthy();
    expect((screen.getByRole("textbox", { name: "draft" }) as HTMLTextAreaElement).value).toBe("还没发出去的话");
    expect(document.documentElement.lang).toBe("en");
    await waitFor(() => expect(puts).toEqual(['{"language":"en"}']));
    await waitFor(() => expect(localStorage.getItem(LANGUAGE_KEY)).toBe("en"));
    expect(screen.getByRole("radio", { name: "English" }).getAttribute("aria-checked")).toBe("true");
  });

  // 设计 Error Handling：存不上时换回原来的并提示
  it("switches back when saving fails", async () => {
    fakeBackend({ username: "yi", language: null }, 500);
    mount();
    await openLanguagePane();
    await userEvent.click(screen.getByRole("radio", { name: "日本語" }));
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toBe("没能保存，已恢复原来的选择。");
    expect(currentLanguage()).toBe("zh");
    expect(screen.getByRole("dialog", { name: "设置" })).toBeTruthy();
    expect(localStorage.getItem(LANGUAGE_KEY)).toBeNull();
  });

  // 存好之前刷新，第一帧也是刚选的语言：缓存和画面一起当场写（windows-server 任务 13，windows-latest 上的 i18n.spec）
  it("writes the browser cache at once, before the server answers", async () => {
    const puts = fakeBackend({ username: "yi", language: null }, "never");
    mount();
    await openLanguagePane();
    await userEvent.click(screen.getByRole("radio", { name: "English" }));
    expect(puts).toEqual(['{"language":"en"}']);
    expect(localStorage.getItem(LANGUAGE_KEY)).toBe("en");
  });

  // 需求 1.4：跟随浏览器发 null，换成浏览器的语言
  it("following the browser sends null", async () => {
    const puts = fakeBackend({ username: "yi", language: "ja" });
    mount();
    await userEvent.click(await screen.findByRole("button", { name: "言語" }));
    await userEvent.click(screen.getByRole("radio", { name: "ブラウザーの言語に合わせる（中文）" }));
    await waitFor(() => expect(puts).toEqual(['{"language":null}']));
    expect(currentLanguage()).toBe("zh");
    await waitFor(() => expect(localStorage.getItem(LANGUAGE_KEY)).toBeNull());
  });
});

describe("language from the account", () => {
  // 需求 1.5：me 带着存的语言，换成它并写进缓存，刷新时第一帧就对
  it("uses the stored language after me", async () => {
    fakeBackend({ username: "yi", language: "ja" });
    mount();
    await waitFor(() => expect(currentLanguage()).toBe("ja"));
    expect(localStorage.getItem(LANGUAGE_KEY)).toBe("ja");
    expect(document.documentElement.lang).toBe("ja");
  });

  // 需求 1.2、1.7：没登录时跟浏览器，删掉上一个人留下的缓存
  it("signed out follows the browser and clears the cache", async () => {
    localStorage.setItem(LANGUAGE_KEY, "en");
    await act(async () => {
      fakeBackend(null);
      mount();
    });
    await waitFor(() => expect(localStorage.getItem(LANGUAGE_KEY)).toBeNull());
    expect(currentLanguage()).toBe("zh");
  });
});
