import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

// 插件页里有没保存的修改（参数表单、插件默认值）：关对话框或切页签前先问（QAgent 问题 17：没保存就离开，修改悄悄丢了）。

interface Unsaved {
  report: (key: string, dirty: boolean) => void;
}

const UnsavedContext = createContext<Unsaved | null>(null);

/** 表单把自己脏不脏报上来；卸载时自动撤销。不在 {@link UnsavedProvider} 里时什么都不做。 */
export function useReportUnsaved(key: string, dirty: boolean): void {
  const unsaved = useContext(UnsavedContext);
  useEffect(() => {
    if (unsaved === null) return;
    unsaved.report(key, dirty);
    return () => unsaved.report(key, false);
  }, [unsaved, key, dirty]);
}

/** 提供给对话框：`dirty` 是现在有没有没保存的修改。 */
export function useUnsavedState(): { dirty: boolean; provider: (children: ReactNode) => ReactNode } {
  const keys = useRef(new Set<string>());
  const [dirty, setDirty] = useState(false);
  const report = useCallback((key: string, value: boolean) => {
    if (value) keys.current.add(key);
    else keys.current.delete(key);
    setDirty(keys.current.size > 0);
  }, []);
  const value = useMemo(() => ({ report }), [report]);
  const provider = useCallback((children: ReactNode) => <UnsavedContext.Provider value={value}>{children}</UnsavedContext.Provider>, [value]);
  return { dirty, provider };
}
