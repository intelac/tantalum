import { PanelLeft } from "lucide-react";
import type { PointerEvent as ReactPointerEvent, ReactNode } from "react";
import { useCallback, useEffect, useRef, useState } from "react";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import {
  clampWidth,
  modeFor,
  RAIL_WIDTH,
  readCollapsed,
  readWidth,
  saveCollapsed,
  saveWidth,
  type LayoutMode,
} from "./layout";

export interface SidebarState {
  /** 只显示图标。 */
  rail: boolean;
  toggle: () => void;
  close: () => void;
}

interface Props {
  sidebar: (state: SidebarState) => ReactNode;
  header: ReactNode;
  children: ReactNode;
}

function useLayoutMode(): LayoutMode {
  const [mode, setMode] = useState<LayoutMode>(() => modeFor(window.innerWidth));
  useEffect(() => {
    const onResize = (): void => setMode(modeFor(window.innerWidth));
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  return mode;
}

/**
 * 左侧栏加主区。宽屏可拖动（264–420 px，记住宽度）并可收成图标栏；
 * 中等宽度默认图标栏，展开时作为抽屉覆盖；窄屏只有抽屉，顶栏有打开按钮。
 */
export function AppFrame({ sidebar, header, children }: Props) {
  const t = useText();
  const mode = useLayoutMode();
  const [width, setWidth] = useState(readWidth);
  const [collapsed, setCollapsed] = useState(readCollapsed);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const dragging = useRef(false);

  useEffect(() => {
    setDrawerOpen(false);
  }, [mode]);

  const toggle = useCallback(() => {
    if (mode === "wide") {
      setCollapsed((current) => {
        saveCollapsed(!current);
        return !current;
      });
    } else {
      setDrawerOpen((current) => !current);
    }
  }, [mode]);

  const close = useCallback(() => setDrawerOpen(false), []);

  useEffect(() => {
    if (!drawerOpen) return;
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === "Escape") close();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [drawerOpen, close]);

  /**
   * 只有左键按下才开始拖。右键会弹出系统菜单、松开的事件收不到，拖动就一直不结束：之后鼠标只是移过分割线，侧栏也跟着动。
   */
  function onDragStart(event: ReactPointerEvent<HTMLDivElement>): void {
    if (event.button !== 0) return;
    dragging.current = true;
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function onDrag(event: ReactPointerEvent<HTMLDivElement>): void {
    if (!dragging.current) return;
    // 左键已经不按着了（松开的事件丢了）：当作拖完，不跟着移动
    if ((event.buttons & 1) === 0) {
      dragging.current = false;
      return;
    }
    setWidth(clampWidth(event.clientX));
  }

  function onDragEnd(event: ReactPointerEvent<HTMLDivElement>): void {
    if (!dragging.current) return;
    dragging.current = false;
    event.currentTarget.releasePointerCapture(event.pointerId);
    saveWidth(event.clientX);
  }

  const rail = mode === "medium" || (mode === "wide" && collapsed);
  const docked = mode === "wide" || mode === "medium";
  const dockedWidth = rail ? RAIL_WIDTH : width;

  return (
    <div className="flex h-full min-h-0 w-full overflow-hidden bg-canvas text-fg">
      {docked && (
        <aside
          className="relative flex h-full shrink-0 flex-col border-r border-line bg-sidebar"
          style={{ width: dockedWidth }}
          aria-label={t.sidebar.sessions}
        >
          {sidebar({ rail, toggle, close })}
          {mode === "wide" && !collapsed && (
            <div
              role="separator"
              aria-orientation="vertical"
              aria-label={t.sidebar.sessions}
              onPointerDown={onDragStart}
              onPointerMove={onDrag}
              onPointerUp={onDragEnd}
              onPointerCancel={onDragEnd}
              onLostPointerCapture={() => {
                dragging.current = false;
              }}
              className="absolute top-0 -right-1 h-full w-2 cursor-col-resize select-none hover:bg-accent/30"
            />
          )}
        </aside>
      )}

      {drawerOpen && (
        <div className="fixed inset-0 z-40 flex" onMouseDown={(event) => event.target === event.currentTarget && close()}>
          <div className="absolute inset-0 bg-overlay" />
          <aside
            className="relative flex h-full w-[min(var(--sidebar-width),85vw)] flex-col border-r border-line bg-sidebar shadow-xl"
            aria-label={t.sidebar.sessions}
          >
            {sidebar({ rail: false, toggle, close })}
          </aside>
        </div>
      )}

      <div className="flex h-full min-w-0 flex-1 flex-col">
        <header className="flex h-12 shrink-0 items-center gap-2 border-b border-line px-3 sm:px-4">
          {mode === "narrow" && (
            <Button variant="icon" size="sm" aria-label={t.sidebar.expand} onClick={toggle} className={cx("-ml-1")}>
              <PanelLeft size={18} />
            </Button>
          )}
          {header}
        </header>
        <div className="min-h-0 flex-1">{children}</div>
      </div>
    </div>
  );
}
