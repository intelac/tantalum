# Tantalum

**An event-driven foundation for enterprise agents, in Java.** A company runs one server; many people work with the agent in a web console; every step is recorded, so it can be inspected and restored exactly; new behavior comes as plugins, not kernel changes. The next step is the direction DeepSeek calls a Super-Agent: an agent that doesn't only wait to be asked, but listens to the team's messages, review comments and CI results and takes on work when the team's rules allow it (see [Direction](#direction-super-agent)). The detailed manual is in Chinese: [README.md](README.md).

## Event-driven

Everything that happens is an event: a user's message, a model reply, a tool result, the result of a plugin's work. The kernel is a pure function that only decides "given this event, what next"; it does no I/O and never waits. Handlers outside the kernel do the actual work and send the outcome back as a new event.

```
input ring → sequence number → Kernel.step → event and result written as one journal line, flushed → output ring → Handler → result event back to the input ring
```

So a user can steer or queue a follow-up while the model or a tool is running, slow work never blocks other sessions, and a new event source (GitHub, team chat, CI) is one more kind of event rather than a new loop.

## Direction: Super-Agent

At the end of October 2026 DeepSeek open-sourced the research prototype [dsh-superagent](https://github.com/deepseek-ai/dsh-superagent-prototype) and described, in *Exploring Super-agent Design*, a more autonomous agent: it listens to team chat, review comments and CI results, filters them in code first and lets the model judge only what passes; it acts only when someone asks or a team rule allows it; a thin Router hands out work to Workers running in parallel; and a watchdog checks on a timer whether each Worker is really making progress. Tantalum is heading that way. (Tantalum is not affiliated with DeepSeek; this only describes the design direction.)

| Super-Agent idea | What Tantalum already has | Still to build |
|---|---|---|
| Messages, comments and CI results are triggers, filtered in code before the model judges them | Event-driven kernel and input ring | External event sources (GitHub, Feishu and other chat, CI webhooks) and filter rules |
| Act only when asked or when a team rule allows it; an observe mode first records what it would have taken on | Permissions enforced by the server (`PluginGate`, local-workspace limits); the journal records every step | Allow lists, team rules, observe mode |
| A Router that only dispatches, Workers that work in parallel; a light model for the Router, the strongest for Workers | Independent sessions, each with its own model choice | Dispatch and reporting between Router and Worker sessions |
| Long-running tools return at once so the agent keeps replying | Work runs outside the kernel and comes back as an event; users can steer and follow up mid-run | Background tasks and completion notices |
| A watchdog checks on a timer whether Workers are making progress | The journal and trace view record every step | Timer events and a watchdog session |
| The loop runs in the cloud; commands run on the developer's own machine | Local workspace: a connector dials out from the user's computer and runs the tools there | Done |
| Credentials stay out of the model's context | API keys only from environment variables; connector tokens only in a child process's environment | The same for credentials of external systems |

## Why it suits an enterprise

- **Self-hosted and multi-user.** Sessions, journals and memories stay on your own server, which binds to `127.0.0.1` by default. Each user signs in and has their own sessions, memory, plugin switches and console language (Chinese, English, Japanese). Boundaries are enforced by the server, not left to the model: plugins are reached only through `PluginGate`, and API keys never enter the journal, the logs or the model's context.
- **Replayable by construction.** The kernel is a pure function. Every step, together with its result, is written as one line of an append-only journal and flushed to disk before it takes effect. On restart, the recorded results are restored instead of recomputed, so any session can be reopened, audited and re-checked step by step (`java -jar tantalum.jar check`).
- **Reverse sandbox ("local workspace").** The agent stays on the server; file, Git and shell tools run on the user's own Mac through a small connector that dials out over WebSocket. The user decides on their machine which directories are allowed and whether writing and shell are allowed; the server cannot widen that. Access uses web-issued tokens that expire after 30 min, 1 h, 4 h or 1 day (stored only as hashes). Every shell command runs inside Microsoft MXC (macOS Seatbelt, deny-by-default); if isolation is unavailable, the shell tool is simply not offered.
- **Pick the model and thinking level per session.** The admin configures routes: one Claude route plus any number of OpenAI-compatible routes (hosted ones, or a local llama.cpp or vLLM). In the console each user picks a model and thinking level next to the input box and can switch in the middle of a session. The switch is written to the journal, the history goes along, and the context window and compaction point follow the chosen model. API keys are read only from environment variables.
- **Plugins without touching the kernel.** Seven hook points (`beforeModelCall`, `beforeToolCall`, `onFailure`, `beforeRunEnd`, `onWorkResult`, `beforeCompact`, `afterToolExecution`). Hooks are pure; any I/O goes through timed, journaled "work" handled outside the kernel. Example plugins: read-only policy, tool approval, daily quota, provider backoff, citation check, pinned context, output truncation.
- **Everything else an agent needs:** MCP client, automatic context compaction, attachments and images, long-term memory, per-user plugin switches, and a web console with a trace view of every model call, tool call and hook.

## How it was built

Spec-first, by directing AI coding agents: each feature goes through requirements → design → tasks, each approved before work starts, and every requirement cites how two reference agents (pi and DeepSeek Harness) solve the same problem. The specs live in [`.spec-workflow/specs`](.spec-workflow/specs). Every phase ends with the full regression suite green (about 1,700 Java tests, ~340 console unit tests, ~45 browser end-to-end tests).

## Layout

| Module | Contents |
|---|---|
| `tantalum-value` | Immutable types: events, commands, state |
| `tantalum-kernel` | Pure logic: transition table and hooks, no I/O |
| `tantalum-shell` | All I/O: rings, journal, handlers, model clients, web |
| `tantalum-local` | Tools that run on the user's computer, MXC sandbox, wire format |
| `tantalum-connect` | The connector the user runs on their Mac; macOS app bundle |
| `tantalum-examples` | Example plugins |
| `console/` | Reference web console (React) |

Dependencies point one way, `value ← kernel ← shell`, enforced by ArchUnit.

## Run

Requires JDK 25, Maven 3.9, Node 25 and pnpm.

```sh
(cd console && pnpm install && pnpm build)
mvn clean package -DskipTests
export ANTHROPIC_API_KEY=...
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar
```

To keep everything local, add an OpenAI-compatible route and make it the default, for example a local llama.cpp:

```sh
java -jar tantalum-shell/target/tantalum-shell-0.1.0-SNAPSHOT-exec.jar --tantalum.provider=local \
  --tantalum.openai-routes.local.base-url=http://127.0.0.1:8090/v1 \
  --tantalum.openai-routes.local.model=qwen3.8-flash-next-unsloth-ud-q4_k_xl \
  --tantalum.openai-routes.local.thinking=chat-template
```

Open <http://127.0.0.1:8080>. The server binds to `127.0.0.1` by default.

## Languages

The console, the connector and the command line speak Chinese, English and Japanese.

- The console follows the browser's language by default (Traditional Chinese browsers get Simplified Chinese; anything else gets English). Pick another under Settings › Language: it applies at once and is saved to your account, so other computers use it too. Choose "Use browser language" to go back. The sign-in page always follows the browser.
- `tantalum-connect` and the `users`, `check` and `browser install` commands follow `LC_ALL`, `LC_MESSAGES`, `LANG`, then the system language, or take `--lang zh|en|ja`.
- API errors carry a code and parameters (`{"error": …, "code": "attachment/image-too-large", "params": {"limit": 52428800}}`); the console words them in the viewer's language. The list is `console/src/api/errorCodes.json`.
- Text for the model is never translated: the model receives the same request whatever the console language. Server logs stay in English.
- Plugin authors can describe plugins and ask questions in several languages with `LocalText.en("…").zh("…").ja("…")`; a plain `LocalText.of("…")` is shown as written.

## License

MIT, see [LICENSE](LICENSE). Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
