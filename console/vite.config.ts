import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

const API = process.env.TANTALUM_API ?? "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // 后端不在默认端口时用 TANTALUM_API 指过去，如 TANTALUM_API=http://127.0.0.1:8089 pnpm dev。
    // 后端校验 Origin 和主机一致，开发时经代理转发要把 Origin 改成后端自己的，否则写操作 403。
    proxy: {
      "/api": {
        target: API,
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on("proxyReq", (request) => {
            if (request.getHeader("origin") !== undefined) request.setHeader("origin", API);
          });
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
  },
});
