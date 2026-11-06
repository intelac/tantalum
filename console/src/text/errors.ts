// 接口错误说给人听（console-i18n I9）：服务器只给代码和参数，这里在渲染时按当前语言取字典里的文字，
// 参数里的字节数、数字按当前语言格式化。认不出的代码退回“请求失败（状态码）”，英文诊断放在下面小字里。

import { ApiError, NetworkError } from "../api/client";
import type { ErrorParam } from "../api/types";
import { currentLanguage, currentText, lookup, type Text } from "./index";
import { formatBytes, formatChoices, formatNumber } from "./format";

export interface ErrorText {
  message: string;
  /** 认不出代码时的英文诊断；认得时为 null。 */
  detail: string | null;
}

function number(value: ErrorParam | undefined): number {
  return typeof value === "number" ? value : Number(value);
}

function text(value: ErrorParam | undefined): string {
  if (value === undefined) return "";
  return Array.isArray(value) ? value.join(", ") : String(value);
}

/** 服务器错误代码的文字；字典里没有这个代码时为 undefined。 */
export function codeText(t: Text, code: string, params: Readonly<Record<string, ErrorParam>>): string | undefined {
  const e = t.errors;
  const bytes = (name: string) => formatBytes(number(params[name]));
  const count = (name: string) => formatNumber(number(params[name]));
  const level = (id: string) => lookup(t.composer.picker.levelNames, id) ?? id;
  const levels = () => formatChoices((Array.isArray(params.levels) ? params.levels : []).map(level), currentLanguage());
  switch (code) {
    case "attachment/too-large":
      return e["attachment/too-large"](bytes("limit"));
    case "attachment/image-too-large":
      return e["attachment/image-too-large"](bytes("limit"));
    case "attachment/image-edge":
      return e["attachment/image-edge"](count("width"), count("height"), count("limit"));
    case "attachment/image-pixels":
      return e["attachment/image-pixels"](count("width"), count("height"), count("limit"));
    case "attachment/too-many-images":
      return e["attachment/too-many-images"](count("max"), count("count"));
    case "attachment/images-too-large":
      return e["attachment/images-too-large"](bytes("limit"));
    case "attachment/too-many-files":
      return e["attachment/too-many-files"](count("max"), count("count"));
    case "model/route-unavailable":
      return e["model/route-unavailable"](text(params.route));
    case "model/not-offered":
      return e["model/not-offered"](text(params.model));
    case "model/no-levels":
      return e["model/no-levels"](text(params.model));
    case "model/level-required":
      return e["model/level-required"](text(params.model), levels());
    case "model/level-unknown":
      return e["model/level-unknown"](text(params.model), level(text(params.level)), levels());
    case "model/tool-changes":
      return e["model/tool-changes"](text(params.model));
    case "plugin/not-found":
      return e["plugin/not-found"](text(params.name));
    case "plugin/required":
      return e["plugin/required"](text(params.name));
    case "memory/invalid": {
      const field = text(params.field);
      const fields: Record<string, string> = { type: t.memory.type, description: t.memory.description, content: t.memory.content };
      return e["memory/invalid"](fields[field] ?? field);
    }
    case "memory/too-long":
      return e["memory/too-long"](bytes("limit"));
    case "memory/full":
      return e["memory/full"](count("max"));
    case "memory/index-full":
      return e["memory/index-full"](bytes("limit"));
  }
  const plain = lookup(e, code);
  return typeof plain === "string" ? plain : undefined;
}

/** 本机命令行用不了的原因（console-i18n I12）：连接程序送来的代码；认不出时为 undefined。`detail`、`path` 原样放进去。 */
export function sandboxText(t: Text, code: string, params: Readonly<Record<string, string>>): string | undefined {
  const s = t.plugins.local.sandbox;
  switch (code) {
    case "sandbox/mxc-invalid":
      return s["sandbox/mxc-invalid"](params.path ?? "");
    case "sandbox/temp-dir":
      return s["sandbox/temp-dir"](params.detail ?? "");
    case "sandbox/check-failed":
      return s["sandbox/check-failed"](params.detail ?? "");
    case "sandbox/start-failed":
      return s["sandbox/start-failed"](params.detail ?? "");
    case "sandbox/run-failed":
      return s["sandbox/run-failed"](params.detail ?? "");
  }
  const plain = lookup(s, code);
  return typeof plain === "string" ? plain : undefined;
}

/** 显示一个失败：在渲染时调，切换语言后已经显示着的错误也跟着换。 */
export function errorText(failure: unknown): ErrorText {
  const t = currentText();
  if (failure instanceof NetworkError) return { message: t.attachment.networkError, detail: null };
  if (failure instanceof ApiError) {
    const known = failure.code === null ? undefined : codeText(t, failure.code, failure.params);
    if (known !== undefined) return { message: known, detail: null };
    if (failure.status === 429) return { message: t.error.busy, detail: null };
    if (failure.status === 503) return { message: t.error.halted, detail: null };
    return { message: t.error.request(failure.status), detail: failure.diagnostic === "" ? null : failure.diagnostic };
  }
  return { message: failure instanceof Error ? failure.message : String(failure), detail: null };
}

/** 一行写完：消息，认不出代码时在后面括上英文诊断。给只能放一行字的地方（按钮旁的提示、上传失败）。 */
export function errorLine(failure: unknown): string {
  const { message, detail } = errorText(failure);
  return detail === null ? message : `${message} (${detail})`;
}
