// 测试用的模型列表（model-picker）：Claude 三个模型（Sonnet 5 不支持会话中途工具变化），本地 qwen 关/开，另一条连不上的路线。

import type { ModelInfo, ModelsResponse } from "../api/types";

export function model(id: string, name: string, levels: string[], defaultLevel: string | null, extra: Partial<ModelInfo> = {}): ModelInfo {
  return {
    id,
    name,
    window: 1_000_000,
    windowSource: "model",
    maxTokens: 64_000,
    vision: true,
    levels,
    defaultLevel,
    toolChanges: true,
    points: [
      { percent: 50, tokens: 500_000 },
      { percent: 70, tokens: 700_000 },
      { percent: 85, tokens: 850_000 },
      { percent: 100, tokens: 936_000 },
    ],
    ...extra,
  };
}

const CLAUDE_LEVELS = ["low", "medium", "high", "xhigh", "max"];

export const MODELS: ModelsResponse = {
  routes: [
    {
      name: "claude",
      displayName: "Claude",
      kind: "claude",
      cloud: true,
      status: "ok",
      error: null,
      models: [
        model("claude-sonnet-5-5", "Claude Sonnet 5.5", CLAUDE_LEVELS, "medium"),
        model("claude-sonnet-4-6", "Claude Sonnet 4.6", ["low", "medium", "high", "max"], "medium"),
        model("claude-sonnet-5", "Claude Sonnet 5", CLAUDE_LEVELS, "medium", { toolChanges: false }),
      ],
    },
    {
      name: "local",
      displayName: "本地",
      kind: "openai",
      cloud: false,
      status: "ok",
      error: null,
      models: [
        model("qwen3.8", "qwen3.8", ["off", "on"], "on", {
          window: 131_072,
          windowSource: "config",
          maxTokens: 16_384,
          vision: false,
          points: [
            { percent: 50, tokens: 65_536 },
            { percent: 70, tokens: 91_750 },
            { percent: 85, tokens: 111_411 },
            { percent: 100, tokens: 114_688 },
          ],
        }),
        model("plain", "plain", [], null, { window: 32_000 }),
      ],
    },
    { name: "kimi", displayName: "Kimi", kind: "openai", cloud: true, status: "unavailable", error: "401", models: [] },
  ],
  default: { route: "claude", model: "claude-sonnet-5-5", effort: "medium" },
  mine: null,
  percent: 100,
};
