// vitest 启动文件：已有的断言是中文的，固定用中文跑（console-i18n I16）。英文、日文的测试自己切换。
// jsdom 的浏览器语言是 en-US；登录后“跟随浏览器”会按它换语言，所以这里把浏览器也当成中文的。

import { beforeEach } from "vitest";
import { setLanguage } from "../text";

// 测试文件的模块加载前先定成中文：哪个模块在顶层取了字典，英文的渲染测试（text/english.test.tsx）就会看到中文
setLanguage("zh");

beforeEach(() => {
  Object.defineProperty(navigator, "languages", { value: ["zh-CN"], configurable: true });
  Object.defineProperty(navigator, "language", { value: "zh-CN", configurable: true });
  setLanguage("zh");
});
