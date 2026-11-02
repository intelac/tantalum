// 把条目顺序折成渲染块：用户消息单独一块；同一运行的条目一块，其中结尾的文本是回复，其余进过程区。

import type { Item } from "../api/types";

export type Block =
  | { kind: "user"; id: string }
  | { kind: "compaction"; id: string }
  | {
      kind: "run";
      run: string;
      /** 思考、工具步骤和中间文本，按发生顺序。 */
      process: string[];
      /** 结尾连续的文本段，是这轮的回复。 */
      reply: string[];
    };

type Shape = Pick<Item, "kind" | "run">;

export function groupBlocks(order: readonly string[], shapeOf: (id: string) => Shape | undefined): Block[] {
  const blocks: Block[] = [];
  const open = new Map<string, { id: string; text: boolean }[]>();

  for (const id of order) {
    const shape = shapeOf(id);
    if (shape === undefined) continue;
    if (shape.kind === "compaction") {
      blocks.push({ kind: "compaction", id });
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
      blocks.push({ kind: "run", run: shape.run, process: [], reply: [] });
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
