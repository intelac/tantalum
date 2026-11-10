// 工作区路径是用户电脑上的路径，这里只按文字处理。规则和服务器的 ProjectPaths 一模一样，
// 两边用同一张用例表（tantalum-shell/src/test/resources/project/paths.json）。

const DRIVE = /^[A-Za-z]:[\\/]/;

/** Unix 的 `/…`，或 Windows 的 `X:\…`、`X:/…`。网络路径、`~`、相对路径都不算。 */
export function isAbsolute(path: string): boolean {
  const trimmed = path.trim();
  return trimmed.startsWith("/") || DRIVE.test(trimmed);
}

/** 判断两个路径是不是同一个目录用的键：去首尾空白和末尾分隔符；Windows 路径统一成 `/` 并转小写。 */
export function pathKey(path: string): string {
  const trimmed = path.trim();
  if (DRIVE.test(trimmed)) return stripTrailing(trimmed.replaceAll("\\", "/").toLowerCase(), 3);
  return stripTrailing(trimmed, 1);
}

/** 默认名字：最后一段。根目录没有最后一段，用路径本身。 */
export function folderName(path: string): string {
  const trimmed = path.trim();
  const windows = DRIVE.test(trimmed);
  const stripped = stripTrailing(windows ? trimmed.replaceAll("\\", "/") : trimmed, windows ? 3 : 1);
  const last = stripped.slice(stripped.lastIndexOf("/") + 1);
  return last === "" || (windows && stripped.length <= 3) ? trimmed : last;
}

/** `dir` 是不是 `root` 本身或它下面的目录。 */
export function within(dir: string, root: string): boolean {
  const inner = pathKey(dir);
  const outer = pathKey(root);
  return inner === outer || inner.startsWith(outer.endsWith("/") ? outer : `${outer}/`);
}

function stripTrailing(path: string, keep: number): string {
  let end = path.length;
  while (end > keep && (path[end - 1] === "/" || path[end - 1] === "\\")) end--;
  return path.slice(0, end);
}
