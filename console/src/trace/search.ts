// 搜索：每条记录的可搜索文字（按记录对象缓存），覆盖收起的内容。system 原文和请求消息要按需加载，不在搜索范围。

import type { TraceRecord } from "../api/types";

const cache = new WeakMap<TraceRecord, string>();

function json(value: unknown): string {
  if (value === undefined || value === null) return "";
  return typeof value === "string" ? value : JSON.stringify(value);
}

function fields(record: TraceRecord): (string | undefined)[] {
  switch (record.kind) {
    case "run":
      return [record.trigger, record.endReason, record.endDetail];
    case "system":
      return [...record.added, ...record.removed, ...record.segments.map((segment) => segment.source.name)];
    case "user":
      return [record.text, record.source.name];
    case "context":
      return [...record.sections.flatMap((section) => [section.name, section.before, section.after]), ...(record.toolsAdded ?? []),
        ...(record.toolsRemoved ?? [])];
    case "compacted":
      return [record.trigger, record.reason, record.summary];
    case "request":
      return [record.text, record.thinking, record.partial, record.failure, record.actualModel, ...record.attempts.map((attempt) => attempt.detail)];
    case "tool":
      return [record.name, record.server, record.rawArguments, record.result, record.reason, record.code];
    case "work":
      return [record.workType, record.hook, json(record.input), json(record.result), record.continueText];
    case "hook":
      return [record.hook, record.point, record.result, record.reason, record.workType, ...record.tools];
    case "question":
      return [record.askedBy, typeof record.title === "string" ? record.title : record.title.en, record.status, record.choice, record.text];
  }
}

/** 记录的可搜索文字，小写。 */
export function searchText(record: TraceRecord): string {
  let text = cache.get(record);
  if (text === undefined) {
    text = fields(record)
      .filter((field): field is string => field !== undefined && field !== "")
      .join("\n")
      .toLowerCase();
    cache.set(record, text);
  }
  return text;
}

/** 匹配的记录 ID；空白的搜索词不匹配任何记录。 */
export function matchRecords(records: readonly TraceRecord[], query: string): ReadonlySet<string> {
  const needle = query.trim().toLowerCase();
  if (needle === "") return new Set();
  return new Set(records.filter((record) => searchText(record).includes(needle)).map((record) => record.id));
}
