import { describe, expect, it } from "vitest";
import table from "../../../tantalum-shell/src/test/resources/project/paths.json";
import { folderName, isAbsolute, pathKey, within } from "./pathKey";

// workspaces 任务 17（W2、W4）：和服务器 ProjectPathsTest 用同一张表。
describe("pathKey", () => {
  for (const item of table.cases) {
    it(`[${item.path}]`, () => {
      expect(isAbsolute(item.path)).toBe(item.absolute);
      if (item.absolute) {
        expect(pathKey(item.path)).toBe(item.key);
        expect(folderName(item.path)).toBe(item.name);
      }
    });
  }
  for (const item of table.within) {
    it(`[${item.dir}] in [${item.root}]`, () => {
      expect(within(item.dir, item.root)).toBe(item.within);
    });
  }
});
