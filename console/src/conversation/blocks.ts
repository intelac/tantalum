// 把条目顺序折成渲染块：用户消息单独一块；同一运行的条目一块，其中结尾的文本是回复，其余进过程区。

import type { Item } from "../api/types";

export type Block =
  | { kind: "user"; id: string }
  | {
      kind: "run";
      run: string;
      statusId?: string;
      /** 思考、工具步骤和中间文本，按发生顺序。 */
      process: string[];
      /** 结尾连续的文本段，是这轮的回复。 */
      reply: string[];
    };

type Shape = Pick<Item, "kind" | "run">;

export function groupBlocks(order: readonly string[], shapeOf: (id: string) => Shape | undefined): Block[] {
  const blocks: Block[] = [];
  const open = new Map<string, { statusId?: string; all: { id: string; text: boolean }[] }>();

  for (const id of order) {
    const shape = shapeOf(id);
    if (shape === undefined) continue;
    if (shape.kind === "user" || shape.run === undefined) {
      blocks.push({ kind: "user", id });
      continue;
    }
    let block = open.get(shape.run);
    if (block === undefined) {
      block = { all: [] };
      open.set(shape.run, block);
      blocks.push({ kind: "run", run: shape.run, process: [], reply: [] });
    }
    if (shape.kind === "run") block.statusId = id;
    else block.all.push({ id, text: shape.kind === "text" });
  }

  for (const block of blocks) {
    if (block.kind !== "run") continue;
    const collected = open.get(block.run);
    if (collected === undefined) continue;
    block.statusId = collected.statusId;
    let split = collected.all.length;
    while (split > 0 && collected.all[split - 1]?.text) split--;
    block.process = collected.all.slice(0, split).map((entry) => entry.id);
    block.reply = collected.all.slice(split).map((entry) => entry.id);
  }
  return blocks;
}
