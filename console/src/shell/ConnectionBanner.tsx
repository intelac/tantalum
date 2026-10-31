import { useConnection } from "../app/connection";
import { zh } from "../text/zh";

/** 顶部的连接提示：断线时提示并自动重连；收到 503 后说明服务已停止处理事件。 */
export function ConnectionBanner() {
  const connection = useConnection();
  if (connection.halted) {
    return (
      <p role="alert" className="shrink-0 bg-danger-soft px-4 py-1.5 text-center text-xs text-danger">
        {zh.connection.halted}
      </p>
    );
  }
  if (connection.lost) {
    return (
      <p role="status" className="shrink-0 bg-warning/15 px-4 py-1.5 text-center text-xs text-fg">
        {zh.connection.offline}
      </p>
    );
  }
  return null;
}
