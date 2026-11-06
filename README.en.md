# Tantalum

A self-hosted, multi-user agent runtime in Java. The detailed manual is in Chinese: [README.md](README.md).

## What it is

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

## License

MIT, see [LICENSE](LICENSE). Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
