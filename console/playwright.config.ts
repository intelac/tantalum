import { defineConfig, devices } from "@playwright/test";

// 端到端：后端是 tantalum-examples 测试目录里的 E2eServer（脚本化模型，不访问外网），
// 前端是 vite 开发服务器经代理转发。两个端口都和日常开发用的（8080、5173）错开。
const API_PORT = 8097;
const WEB_PORT = 5174;

export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    locale: "zh-CN",
    colorScheme: "light",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: `mvn -q -f ../pom.xml -P e2e -Dtantalum.e2e.port=${API_PORT} -pl tantalum-examples -am -DskipTests test-compile`,
      // 未登录返回 401，Playwright 把 401 也当作“已就绪”。
      url: `http://127.0.0.1:${API_PORT}/api/config`,
      reuseExistingServer: true,
      timeout: 240_000,
      stdout: "ignore",
      stderr: "pipe",
    },
    {
      command: `pnpm exec vite --port ${WEB_PORT} --strictPort`,
      env: { TANTALUM_API: `http://127.0.0.1:${API_PORT}` },
      url: `http://localhost:${WEB_PORT}/login`,
      reuseExistingServer: true,
      timeout: 60_000,
      stdout: "ignore",
      stderr: "pipe",
    },
  ],
});
