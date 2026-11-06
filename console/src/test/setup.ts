// vitest 启动文件：已有的断言是中文的，固定用中文跑（console-i18n I16）。英文、日文的测试自己切换。

import { beforeEach } from "vitest";
import { setLanguage } from "../text";

beforeEach(() => {
  setLanguage("zh");
});
