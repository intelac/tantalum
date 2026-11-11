import type { PluginLock } from "../api/types";
import { type Text, useText } from "../text";
import { Button } from "../ui/Button";

export function lockText(text: Text["plugins"], lock: PluginLock): string {
  if (lock.source === "admin") return text.lock.admin(lock.reason);
  return lock.source === "code" ? text.lock.code : text.lock.config;
}

/** 锁定条（plugin-settings 需求 6.2）：“锁定：原因”；管理员多一个“去插件默认值改”。窄屏时原因换行。 */
export function LockNote({ lock, onDefaults }: { lock: PluginLock; onDefaults?: () => void }) {
  const t = useText();
  return (
    <>
      <span className="rounded-xs bg-warning/15 px-1.5 py-0.5 text-[11px] break-words text-fg">{lockText(t.plugins, lock)}</span>
      {onDefaults !== undefined && (
        <Button size="sm" onClick={onDefaults}>
          {t.plugins.lock.goToDefaults}
        </Button>
      )}
    </>
  );
}
