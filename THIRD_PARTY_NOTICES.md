# 第三方声明

本文件列出 Tantalum 依赖或参考的第三方项目及其许可证。版本以 `pom.xml` 和 `console/package.json` 为准；这里的版本号是写下本文件时（2026-11-01）锁定的版本。

## DeepSeek Harness（界面参考）

- 项目：<https://github.com/deepseek-ai/deepseek-harness>，MIT 许可证。
- 用法：控制台 `console/` 的外观和交互（布局、断点、圆角、配色层次、对话区和工具链的组织方式、轨迹页的三行时间条）参照它的 Web 界面设计。设计值是对照截图和源码读出的，组件、样式和协议都是自己写的。
- 没有复制它的任何文件：没有图标、CSS 片段、字体或代码进入本仓库。因此本仓库不需要附带它的版权声明；如果以后直接复制了它的文件，要在该文件头保留出处，并把下面的 MIT 声明补全为它的原文。
- 品牌：按 MIT 不授予商标权的原则，没有使用 DeepSeek 的名称、标志、鲸鱼和小鱼图案。Tantalum 的 “Ta 73” 方格标志是自己画的。

## 后端运行时依赖

| 项目 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| Spring Boot（Web MVC、Security）与 Spring Framework、Spring Security | 4.1.2 | Apache-2.0 | HTTP、SSE、认证、装配 |
| Apache Tomcat（Spring Boot 内嵌） | Spring Boot 管理 | Apache-2.0 | Servlet 容器 |
| Jackson 3 | Spring Boot 管理 | Apache-2.0 | 事件与接口 JSON |
| LMAX Disruptor | 4.0.0 | Apache-2.0 | 输入环、输出环 |
| sqlite-jdbc（含 SQLite） | 3.54.1.0 | Apache-2.0（SQLite 本身为公有领域） | journal 和用户存储 |
| Bouncy Castle `bcprov-jdk18on` | 1.86 | MIT（Bouncy Castle Licence） | Argon2id 口令哈希 |
| anthropic-java（含 OkHttp、Kotlin 标准库、Jackson 2） | 3.3.0 | MIT；OkHttp 与 Kotlin 为 Apache-2.0 | 调用 Claude |
| MCP Java SDK `mcp-core`、`mcp-json-jackson3`（含 Reactor、json-schema-validator） | 2.1.0 | MIT；Reactor 为 Apache-2.0；json-schema-validator 为 Apache-2.0 | 连接 MCP 服务器 |
| SLF4J | Spring Boot 管理 | MIT | 日志门面 |
| Logback | Spring Boot 管理 | EPL-1.0 / LGPL-2.1 双许可 | 日志实现 |

## 控制台运行时依赖

| 包 | 版本 | 许可证 |
|---|---|---|
| react、react-dom | 19.3.1 | MIT |
| @tanstack/react-query | 5.105.0 | MIT |
| react-markdown | 10.1.0 | MIT |
| remark-gfm | 4.0.1 | MIT |
| highlight.js | 11.12.0 | BSD-3-Clause |
| lucide-react | 1.66.0 | ISC |

## 只在构建和测试时使用

| 项目 | 版本 | 许可证 |
|---|---|---|
| JUnit | Spring Boot 管理 | EPL-2.0 |
| ArchUnit | 1.5.1 | Apache-2.0 |
| JaCoCo | 0.8.16 | EPL-2.0 |
| Exec Maven Plugin | 3.6.3 | Apache-2.0 |
| TypeScript | 7.0.2 | Apache-2.0 |
| Vite、@vitejs/plugin-react | 8.3.5、6.1.2 | MIT |
| Tailwind CSS、@tailwindcss/vite | 4.3.3 | MIT |
| vitest | 5.0.5 | MIT |
| jsdom | 30.1.3 | MIT |
| @testing-library/dom、react、user-event | 10.4.1、16.3.3、14.6.8 | MIT |
| @playwright/test | 1.64.1 | Apache-2.0 |
| @types/react、@types/react-dom | 19.3.0 | MIT |

控制台依赖的许可证取自各包 `package.json` 的 `license` 字段；后端依赖的许可证取自各项目的官方声明。发布前应再用工具（如 `license-maven-plugin` 的 `third-party-report`）生成一份完整的传递依赖清单核对。
