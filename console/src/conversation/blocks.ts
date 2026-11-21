// 把条目顺序折成渲染块：用户消息单独一块；同一运行的条目一块，其中结尾的文本是回复，其余进过程区。

import type { Item } from "../api/types";

export type Block =
  | { kind: "user"; id: string }
  | { kind: "compaction"; id: string }
  /** 自动继续到了上限时回来的任务结果：没有开运行，单独一块。 */
  | { kind: "taskResult"; id: string }
  /** 记一笔（dispatch）：别的会话里你直接说了话，单独一块。 */
  | { kind: "note"; id: string }
  | {
      kind: "run";
      run: string;
      /** 思考、工具步骤和中间文本，按发生顺序。 */
      process: string[];
      /** 结尾连续的文本段，是这轮的回复。 */
      reply: string[];
      /** 这轮开始时换了模型、工具变了，显示在过程区上面；没有时不出现。 */
      notices?: string[];
      /** 这轮问用户的问题（waiting-state），显示在过程区下面、回复上面；没有时不出现。 */
      questions?: string[];
      /** 这轮看到的任务结果（subagent），显示在最上面：这一轮就是因为它们才接着做的；没有时不出现。 */
      results?: string[];
      /** 这轮放出的任务，各是一张卡片，显示在过程区下面；没有时不出现。 */
      tasks?: string[];
    };

type Shape = Pick<Item, "kind" | "run"> & { paused?: boolean };

export function groupBlocks(order: readonly string[], shapeOf: (id: string) => Shape | undefined): Block[] {
  const blocks: Block[] = [];
  const open = new Map<string, { id: string; text: boolean }[]>();
  const runs = new Map<string, Extract<Block, { kind: "run" }>>();

  for (const id of order) {
    const shape = shapeOf(id);
    if (shape === undefined) continue;
    if (shape.kind === "compaction") {
      blocks.push({ kind: "compaction", id });
      continue;
    }
    if (shape.kind === "note") {
      blocks.push({ kind: "note", id });
      continue;
    }
    if (shape.kind === "taskResult" && shape.paused === true) {
      blocks.push({ kind: "taskResult", id });
      continue;
    }
    if (shape.kind === "user" || shape.run === undefined) {
      blocks.push({ kind: "user", id });
      continue;
    }
    let entries = open.get(shape.run);
    if (entries === undefined) {
      entries = [];
      open.set(shape.run, entries);
      const block: Extract<Block, { kind: "run" }> = { kind: "run", run: shape.run, process: [], reply: [] };
      runs.set(shape.run, block);
      blocks.push(block);
    }
    if (shape.kind === "toolChange" || shape.kind === "modelChange") {
      const block = runs.get(shape.run);
      if (block !== undefined) block.notices = [...(block.notices ?? []), id];
      continue;
    }
    if (shape.kind === "question") {
      const block = runs.get(shape.run);
      if (block !== undefined) block.questions = [...(block.questions ?? []), id];
      continue;
    }
    if (shape.kind === "taskResult") {
      const block = runs.get(shape.run);
      if (block !== undefined) block.results = [...(block.results ?? []), id];
      continue;
    }
    if (shape.kind === "task") {
      const block = runs.get(shape.run);
      if (block !== undefined) block.tasks = [...(block.tasks ?? []), id];
      continue;
    }
    // 运行状态条目由 RunStatusRow 按运行 ID 自己取，不进过程区也不进回复。
    if (shape.kind !== "run") entries.push({ id, text: shape.kind === "text" });
  }

  for (const block of blocks) {
    if (block.kind !== "run") continue;
    const entries = open.get(block.run) ?? [];
    let split = entries.length;
    while (split > 0 && entries[split - 1]?.text) split--;
    block.process = entries.slice(0, split).map((entry) => entry.id);
    block.reply = entries.slice(split).map((entry) => entry.id);
  }
  return blocks;
}

/**
 * 运行结束了、结尾却不是文本（被停下、到了上限、钩子拦下、最后又调了一次工具）时，最后一段文本才是这轮的回答，
 * 从过程区拿出来当回复（QAgent 问题 59：真正的回答被折进过程区）。运行中不挪，免得文本在过程区和回复之间来回跳。
 */
export function settleReply(
  process: readonly string[],
  reply: readonly string[],
  isText: (id: string) => boolean,
): { process: readonly string[]; reply: readonly string[] } {
  if (reply.length > 0) return { process, reply };
  let last = process.length - 1;
  while (last >= 0 && !isText(process[last] as string)) last--;
  if (last < 0) return { process, reply };
  return { process: [...process.slice(0, last), ...process.slice(last + 1)], reply: [process[last] as string] };
}
