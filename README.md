# Tantalum

English | [中文](README.zh.md)

**An event-driven agent runtime for enterprises, in Java.** One self-hosted server, many users, and a ledger: every step between a person and the agent is written as one line of an append-only journal before it takes effect. Models, tools and plugins come and go; the ledger stays, and anything the agent remembers, knows or does can be traced back to a line in it.

Version **0.1** · [Download](https://github.com/intelac/tantalum/releases/latest) (one jar for macOS, Linux and Windows; JDK 25) · [Roadmap](ROADMAP.md) · MIT

## What it is

A runtime, not a framework. A framework decides how the model thinks and how prompts and tools are arranged. A runtime decides how a piece of work is started by an event, moved forward, paused and resumed, who is allowed to do what, and how it is inspected afterwards.

- **Durable.** Every step and its result is one journal line, flushed to disk before the next step. A restart restores recorded results instead of recomputing them.
- **Event-driven.** A user message, a model reply, a tool result and a plugin's work are all events. The kernel is a pure function: given this event, what next. All I/O happens outside it and comes back as new events, so users can steer mid-run and slow work never blocks other sessions.
- **Rebuildable.** Any session can be replayed step by step; `java -jar tantalum.jar check` verifies that a new version rewrites old sessions byte for byte.
- **Accountable.** Each line carries who acted, in which session and run. The console's trace view shows every model call, tool call and hook.
- **Extensible.** New behavior is a plugin, reached only through one gate and switched per user. Eight hook points; a hook can ask the user a question and wait for days.
- **Bounded by the server, not by the model.** Plugins go through `PluginGate`; file, Git and shell tools run on the user's own machine through a connector, inside a sandbox, within directories the user allows; API keys never enter the journal, the logs or the model's context.

## 0.1 in one list

Multi-user console (Chinese, English, Japanese) · per-session choice of model and thinking level (Claude, or any OpenAI-compatible endpoint such as a local llama.cpp) · append-only journal with replay and `check` · trace view · MCP client · automatic context compaction · attachments and images · per-user long-term memory · plugins with eight hooks and example plugins (read-only policy, tool approval, daily quota, backoff, citation check) · local workspace with a macOS connector and MXC sandbox · web fetch and one headless browser per user · runs on macOS, Linux and Windows.

## Run

```sh
export ANTHROPIC_API_KEY=...
java -jar tantalum-0.1.0.jar          # then open http://127.0.0.1:8080
```

Keep everything local with an OpenAI-compatible route:

```sh
java -jar tantalum-0.1.0.jar --tantalum.provider=local \
  --tantalum.openai-routes.local.base-url=http://127.0.0.1:8090/v1 \
  --tantalum.openai-routes.local.model=<model>
```

Build from source (JDK 25, Maven 3.9, Node 25, pnpm): `(cd console && pnpm install && pnpm build) && mvn clean package -DskipTests`; the jar is `tantalum-shell/target/tantalum-shell-0.1.0-exec.jar`. Windows notes are in [docs/windows](docs/windows).

## Where it is going

The ledger is the center. From it, three things grow as projections rather than separate systems: each person's memory (a wiki, after Alibaba's [ReMe](https://github.com/agentscope-ai/ReMe)), the company's certified knowledge (after NVIDIA's [Auto Ontology](https://github.com/NVIDIA/auto-ontology)), and agents that take on work from external events under team rules (after DeepSeek's [super-agent prototype](https://github.com/deepseek-ai/dsh-superagent-prototype)). The kernel gains five primitives and no new phases: external events, delegation, two more kinds of ownership, agent identities with a responsible person, and a gate hook before every tool call. The first milestone is a falsifiable experiment: two agents with different identities and permissions finish a long job with nobody online, across a process restart, with every step in the ledger and no kernel patches. Details in [ROADMAP.md](ROADMAP.md).

## Layout

| Module | Contents |
|---|---|
| `tantalum-value` | Immutable types: events, commands, state |
| `tantalum-kernel` | Pure logic: transition table and hooks, no I/O |
| `tantalum-shell` | All I/O: rings, journal, handlers, model clients, web |
| `tantalum-local` | Tools that run on the user's computer, MXC sandbox, wire format |
| `tantalum-connect` | The connector the user runs on their Mac |
| `tantalum-examples` | Example plugins |
| `console/` | Web console (React) |

Dependencies point one way, `value ← kernel ← shell`, enforced by ArchUnit. Built spec-first by directing AI coding agents: every feature went through requirements, design and tasks before code, and every requirement cites how two reference agents (pi and DeepSeek Harness) solve the same problem.

## Docs

- Full manual (Chinese): [docs/manual.zh.md](docs/manual.zh.md). Covers configuration, model routes, users, the journal format, operations, Windows, memory, attachments, local workspace, web, MCP, writing plugins and testing.
- Windows: [docs/windows](docs/windows).
- License: MIT, see [LICENSE](LICENSE). Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
