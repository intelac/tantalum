import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import type { LocalStatus, Project, SessionSummary } from "../api/types";
import { linkLimits, linkProblem } from "../composer/LinkChip";
import { ProjectChip } from "../composer/ProjectChip";
import { currentText } from "../text";
import { AddProjectDialog } from "./AddProjectDialog";
import { ProjectSidebar } from "./ProjectSidebar";

// workspaces 任务 19–22：侧栏分组和菜单、改名、归档和撤销、添加框、输入框旁的两个标签。

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return { ...actual, renameSession: vi.fn(), setArchived: vi.fn(), addProject: vi.fn(), browseFolders: vi.fn(), deleteProject: vi.fn() };
});

const client = await import("../api/client");
const renameSession = vi.mocked(client.renameSession);
const setArchived = vi.mocked(client.setArchived);
const addProject = vi.mocked(client.addProject);
const browseFolders = vi.mocked(client.browseFolders);
const deleteProject = vi.mocked(client.deleteProject);

const QUATTRO: Project = { id: "p-q", title: "QTR_Quattro_github_3", path: "C:\\APPL\\QTR_Quattro_github_3", created: "2026-11-10T00:00:00Z" };
const WIKI: Project = { id: "p-w", title: "知识库", path: "C:\\quattro-run\\wiki", created: "2026-11-09T00:00:00Z" };

function session(id: string, title: string, more: Partial<SessionSummary> = {}): SessionSummary {
  return { id, title, lastActivity: "2026-11-10T10:00:00Z", runStatus: "idle", ...more };
}

function withQueries(ui: ReactNode) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{ui}</QueryClientProvider>);
}

beforeEach(() => {
  renameSession.mockResolvedValue({ title: "x" });
  setArchived.mockResolvedValue({ archived: true });
  deleteProject.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  sessionStorage.clear();
  vi.clearAllMocks();
});

describe("ProjectSidebar", () => {
  const sessions = [
    session("s1", "帮我调查一下有没有jira", { dir: QUATTRO.path }),
    session("s2", "index.md 第一行写的是什么", { dir: "c:/quattro-run/wiki/" }),
    session("s3", "分支检查"),
  ];

  function mount(onNewSessionIn = vi.fn()) {
    withQueries(
      <ProjectSidebar
        sessions={sessions}
        projects={[QUATTRO, WIKI]}
        currentSession={null}
        onSelectSession={() => undefined}
        onNewSessionIn={onNewSessionIn}
        onAddProject={() => undefined}
      />,
    );
  }

  it("groups by workspace with counts and puts the ungrouped ones last", () => {
    mount();
    const headers = screen.getAllByRole("button", { expanded: true }).map((button) => button.textContent);
    expect(headers).toEqual(["QTR_Quattro_github_31", "知识库1", "未分组1"]);
  });

  it("collapses a group and remembers it", async () => {
    mount();
    await userEvent.click(screen.getByRole("button", { name: "收起 知识库" }));
    expect(screen.queryByText("index.md 第一行写的是什么")).toBeNull();
    cleanup();
    mount();
    expect(screen.queryByText("index.md 第一行写的是什么")).toBeNull();
    expect(screen.getByRole("button", { name: "展开 知识库" })).toBeTruthy();
  });

  it("searches titles and workspace names and shows the workspace under each hit", async () => {
    mount();
    await userEvent.type(screen.getByRole("searchbox", { name: "搜索会话" }), "知识库");
    const results = screen.getByRole("navigation");
    expect(within(results).getByText("index.md 第一行写的是什么")).toBeTruthy();
    expect(within(results).queryByText("分支检查")).toBeNull();
    await userEvent.keyboard("{Escape}");
    expect(within(results).getByText("分支检查")).toBeTruthy();
  });

  it("renames a session through the dialog", async () => {
    mount();
    await userEvent.dblClick(screen.getByText("分支检查"));
    const name = screen.getByRole("textbox", { name: "名字" });
    await userEvent.clear(name);
    await userEvent.type(name, "Quattro 分支{Enter}");
    await waitFor(() => expect(renameSession).toHaveBeenCalledWith("s3", "Quattro 分支"));
  });

  it("archives a session and can undo it", async () => {
    mount();
    await userEvent.click(screen.getByRole("button", { name: "分支检查 的操作" }));
    await userEvent.click(screen.getByRole("menuitem", { name: "归档" }));
    await waitFor(() => expect(setArchived).toHaveBeenCalledWith("s3", true));
    await userEvent.click(await screen.findByRole("button", { name: "撤销" }));
    await waitFor(() => expect(setArchived).toHaveBeenLastCalledWith("s3", false));
  });

  it("does not archive a running session and says why", async () => {
    withQueries(
      <ProjectSidebar
        sessions={[session("s9", "还在跑", { runStatus: "AwaitingModel" })]}
        projects={[]}
        currentSession={null}
        onSelectSession={() => undefined}
        onNewSessionIn={() => undefined}
        onAddProject={() => undefined}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: "还在跑 的操作" }));
    await userEvent.click(screen.getByRole("menuitem", { name: "归档" }));
    expect(screen.getByRole("status").textContent).toContain("运行中或等你回答时不能归档");
    expect(setArchived).not.toHaveBeenCalled();
  });

  it("starts a new session in a workspace and deletes one after confirming", async () => {
    const onNewSessionIn = vi.fn();
    mount(onNewSessionIn);
    await userEvent.click(screen.getByRole("button", { name: "工作区 知识库 的操作" }));
    await userEvent.click(screen.getByRole("menuitem", { name: "在此新建会话" }));
    expect(onNewSessionIn).toHaveBeenCalledWith(WIKI);
    await userEvent.click(screen.getByRole("button", { name: "工作区 知识库 的操作" }));
    await userEvent.click(screen.getByRole("menuitem", { name: "删除" }));
    const dialog = screen.getByRole("dialog");
    expect(dialog.textContent).toContain("回到“未分组”");
    await userEvent.click(within(dialog).getByRole("button", { name: "删除" }));
    await waitFor(() => expect(deleteProject).toHaveBeenCalledWith("p-w"));
  });
});

describe("AddProjectDialog", () => {
  it("browses the computer's folders, picks one and names it after the folder", async () => {
    browseFolders.mockImplementation(async (path) =>
      path === undefined
        ? { path: null, dirs: ["C:\\APPL"], truncated: false }
        : { path, dirs: [`${path}\\QTR_Quattro_github_3`], truncated: false },
    );
    addProject.mockResolvedValue(QUATTRO);
    const onAdded = vi.fn();
    withQueries(<AddProjectDialog open onClose={() => undefined} onAdded={onAdded} />);
    await userEvent.click(await screen.findByRole("button", { name: /C:\\APPL/ }));
    await userEvent.click(await screen.findByRole("button", { name: /QTR_Quattro_github_3/ }));
    await userEvent.click(await screen.findByRole("button", { name: "选这个目录" }));
    expect((screen.getByRole("textbox", { name: "目录" }) as HTMLInputElement).value).toBe("C:\\APPL\\QTR_Quattro_github_3");
    expect(screen.getByRole("textbox", { name: "名字" }).getAttribute("placeholder")).toBe("QTR_Quattro_github_3");
    await userEvent.click(screen.getByRole("button", { name: "添加" }));
    await waitFor(() => expect(addProject).toHaveBeenCalledWith("C:\\APPL\\QTR_Quattro_github_3", undefined));
    expect(onAdded).toHaveBeenCalledWith(QUATTRO);
  });

  it("explains an offline computer and still lets a path be typed; shows the server's reason on failure", async () => {
    browseFolders.mockRejectedValue(new ApiError(409, "local/not-connected", {}, "the computer is not connected"));
    addProject.mockRejectedValue(new ApiError(409, "project/path-taken", {}, "taken"));
    withQueries(<AddProjectDialog open onClose={() => undefined} />);
    expect(await screen.findByText("电脑没连上，只能手动输入路径")).toBeTruthy();
    const add = screen.getByRole("button", { name: "添加" }) as HTMLButtonElement;
    expect(add.disabled).toBe(true);
    await userEvent.type(screen.getByRole("textbox", { name: "目录" }), "/Users/yi/app");
    expect(screen.getByText("电脑没连上：先登记，连上后再核对")).toBeTruthy();
    await userEvent.click(add);
    expect(await screen.findByRole("alert")).toHaveProperty("textContent", "这个目录已经是一个工作区了");
  });
});

describe("composer chips", () => {
  const t = currentText();
  const link = {
    device: { name: "m", os: "windows", osVersion: "11", arch: "x64" },
    roots: [{ path: "C:\\APPL" }],
    git: true,
    write: true,
    shell: { available: true },
    network: true,
    protocol: 3,
    current: true,
    connectedAt: "",
    expiresAt: "",
  };
  const on = { "local-workspace": true, "fs-read": true, git: true, "file-edit": true, shell: true };

  it("names the first thing wrong with the computer, or nothing", () => {
    const status = (more: Partial<LocalStatus>): LocalStatus => ({ switches: on, link, ...more });
    expect(linkProblem(status({}), "C:\\APPL\\Quattro", t)).toBeNull();
    expect(linkProblem(status({ switches: { ...on, "local-workspace": false } }), "C:\\APPL\\Quattro", t)).toBe("文件工具已关闭");
    expect(linkProblem(status({ link: null }), "C:\\APPL\\Quattro", t)).toBe("电脑没连上");
    expect(linkProblem(status({ link: { ...link, current: false } }), "C:\\APPL\\Quattro", t)).toBe("连接程序要更新");
    expect(linkProblem(status({}), "D:\\other", t)).toBe("电脑不允许这个目录");
    expect(linkProblem(undefined, "C:\\APPL", t)).toBeNull();
    // 开了开关而电脑那边没给：排在拦路的毛病后面，开关关着就不提
    const weak = { ...link, write: false, shell: { available: false } };
    const here = "C:\\APPL\\Quattro";
    expect(linkLimits(status({}), here, t)).toEqual([]);
    expect(linkLimits(status({ link: weak }), here, t)).toEqual([
      { label: "不能改文件", hint: "连接程序没有允许：启动时加 --allow-write 才能改文件" },
      { label: "不能跑命令", hint: "连接程序没有允许：启动时加 --allow-shell 才能跑命令" },
    ]);
    expect(linkLimits(status({ link: { ...weak, write: true } }), here, t).map((limit) => limit.label)).toEqual(["不能跑命令"]);
    expect(linkLimits(status({ link: weak }), "D:\\other", t)).toEqual([]);
    expect(linkLimits(status({ link: weak, switches: { ...on, "file-edit": false, shell: false } }), here, t)).toEqual([]);
  });

  it("lets a new session pick a workspace and shows only the name once it started", async () => {
    const onChange = vi.fn();
    const onAdd = vi.fn();
    const { rerender } = render(<ProjectChip projects={[QUATTRO, WIKI]} value={null} fixed={false} onChange={onChange} onAdd={onAdd} />);
    await userEvent.click(screen.getByRole("button", { name: "工作区: 不选（未分组）" }));
    await userEvent.click(screen.getByRole("menuitem", { name: "知识库" }));
    expect(onChange).toHaveBeenCalledWith(WIKI);
    await userEvent.click(screen.getByRole("button", { name: "工作区: 不选（未分组）" }));
    await userEvent.click(screen.getByRole("menuitem", { name: "添加工作区…" }));
    expect(onAdd).toHaveBeenCalled();
    rerender(<ProjectChip projects={[QUATTRO, WIKI]} value={WIKI} fixed onChange={onChange} onAdd={onAdd} />);
    expect(screen.queryByRole("button", { name: /工作区/ })).toBeNull();
    expect(screen.getByText("知识库").closest("span[title]")?.getAttribute("title")).toContain("C:\\quattro-run\\wiki");
  });
});
