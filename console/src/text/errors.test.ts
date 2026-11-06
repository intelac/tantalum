import { describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "../api/client";
import codes from "../api/errorCodes.json";
import { currentText } from "./index";
import { codeText, errorText, sandboxText } from "./errors";

// 需求 4.2–4.4：服务器只给代码和参数，控制台按当前语言说；认不出的退回“请求失败（状态码）”加英文诊断。
const MIB = 1024 * 1024;

/** 每种参数给一个样例值，能把所有代码都说一遍。 */
const SAMPLE = { limit: 50 * MIB, width: 70000, height: 10, max: 20, count: 21, route: "kimi", model: "qwen", level: "high", levels: ["off", "on"], name: "dailyQuota", field: "type" };

describe("errorText", () => {
  it("says a known code with its parameters formatted", () => {
    expect(errorText(new ApiError(413, "attachment/image-too-large", { limit: 50 * MIB }, "the image is larger than the limit of 52428800 bytes"))).toEqual({
      message: "单张图片不能超过 50 MB",
      detail: null,
    });
    expect(errorText(new ApiError(400, "attachment/too-many-images", { max: 20, count: 1234 }, "")).message).toBe("一条消息最多 20 张图片，这条有 1,234 张");
    expect(errorText(new ApiError(400, "model/level-unknown", { model: "qwen3.8", level: "high", levels: ["off", "on"] }, "")).message).toBe(
      "qwen3.8 没有“高”这一档，可选：关或开",
    );
    expect(errorText(new ApiError(400, "memory/invalid", { field: "description" }, "")).message).toBe("这一项没有填对：一句话描述");
  });

  it("falls back to the status with the English diagnostic underneath", () => {
    expect(errorText(new ApiError(418, "teapot/brewing", {}, "short and stout"))).toEqual({ message: "请求失败（418）", detail: "short and stout" });
    expect(errorText(new ApiError(500, null, {}, ""))).toEqual({ message: "请求失败（500）", detail: null });
  });

  it("keeps the fixed wording for 429 and 503 without a code", () => {
    expect(errorText(new ApiError(429, null, {}, "")).message).toBe("系统繁忙，请稍后重试（429）");
    expect(errorText(new ApiError(503, null, {}, "")).message).toBe("服务已停止处理事件（503）");
    // 有代码时按代码说：登录限流、图片处理繁忙不是“系统繁忙”
    expect(errorText(new ApiError(429, "auth/too-many-attempts", {}, "")).message).toBe("尝试次数过多，请稍后再试");
  });

  it("network failures and plain errors", () => {
    expect(errorText(new NetworkError("x")).message).toBe("网络错误，上传没有完成");
    expect(errorText(new Error("boom")).message).toBe("boom");
  });

  it("every server code is in the dictionary and says something", () => {
    expect(Object.keys(currentText().errors).sort()).toEqual([...codes.errors].sort());
    for (const code of codes.errors) {
      const said = codeText(currentText(), code, SAMPLE);
      expect(said, code).toBeTruthy();
      expect(said, code).not.toMatch(/undefined|NaN|\[object/);
    }
  });
});

describe("sandboxText", () => {
  it("every sandbox code is in the dictionary; detail is shown as written", () => {
    expect(Object.keys(currentText().plugins.local.sandbox).sort()).toEqual([...codes.sandbox].sort());
    for (const code of codes.sandbox) expect(sandboxText(currentText(), code, { path: "/opt/mxc", detail: "denied" }), code).toBeTruthy();
    expect(sandboxText(currentText(), "sandbox/start-failed", { detail: "sandbox-exec: denied" })).toBe("MXC 不能启动沙箱：sandbox-exec: denied");
    expect(sandboxText(currentText(), "sandbox/new-thing", {})).toBeUndefined();
  });
});
