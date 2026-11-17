# Tantalum roadmap

English | [中文](ROADMAP.zh.md)

Tantalum is an event-driven foundation for enterprise agents: one server, many users, every step journaled and replayable, new behavior as plugins. 0.1 shipped in November 2026. Below is what comes next, in order, with the reasoning. The full manual is [docs/manual.zh.md](docs/manual.zh.md) (Chinese); questions and ideas are welcome as issues.

## In one sentence

**A company that stops forgetting, with AI that acts in it accountably.**

Tantalum's foundation is a ledger: every step between a person and the AI is one journaled, replayable line (shipped in 0.1). From that ledger, three things that used to be built separately can grow instead: each person's memory (item 1), the company's own emergent knowledge map (the team part of item 1), and agents that act autonomously under the company's rules (items 2 and 3). The more it is used, the thicker the ledger, the better the memory and the map, the more work the agents can take on. Open source has already validated each layer on its own: Alibaba AgentScope's ReMe (memory), NVIDIA's Auto Ontology (knowledge map), DeepSeek's super-agent prototype (action). What they share is the absence of the ledger, and of a multi-user enterprise foundation.

## 1. LLM wiki: a continuously updated long-term memory (designing)

Topics: `long-term-memory` `llm-wiki` `auto-ontology`

A wiki maintained by the LLM and readable and editable by the user, serving as memory across sessions. The ontology (people, projects, decisions, preferences, procedures, terms and the relations between them) is not designed up front; it grows out of pages and `[[links]]`.

- Pages are Markdown files with frontmatter; every paragraph cites its journal step; paragraphs without a source are marked unverified.
- Runs only **append** new facts (visible immediately). Rewriting and merging happen only in a scheduled "librarian" run, triggered by volume and bounded per run, because continuously rewriting memories makes them worse (arXiv 2605.12978).
- The journal stays the primary evidence; pages are an index and a digest, and any page can be rebuilt from the journal.
- Memory has three layers: **personal** (only the owner sees it) → **project / team** (its members) → **company** (by department and permission). Personal memory is private by default.
- Knowledge flows up: the nightly consolidation picks procedures, pitfalls and facts that would help others and strips personal details to make candidates; once the owner agrees, a lead certifies them before they become team or company knowledge. Each item traces back to the conversation it came from, who agreed and who certified it.
- Knowledge flows down: every prompt searches all three layers within the asker's permissions, showing which layer each item comes from and who certified it; where it conflicts with certified company knowledge, the company knowledge wins and the conflict is shown.
- Why a company wants it: a new hire benefits from the team's experience on day one, experience stays when people leave, and the company steps on each pitfall only once; every item has a trail, so it can be audited. The rules are private by default, nothing counts until it is certified, permissions follow the person, and anything can be withdrawn or forgotten.
- References: Alibaba AgentScope's [ReMe](https://github.com/agentscope-ai/ReMe) (layers, memory as files, hook-driven capture, scheduled consolidation), Karpathy's LLM Wiki (raw sources / wiki / schema), NVIDIA's [Auto Ontology](https://github.com/NVIDIA/auto-ontology) (LLM-extracted terms and relations over enterprise data, certified by people before use).


## 2. Stepping out of the session (designing)

Topics: `autonomous-agent` `event-driven`

Once the wiki exists, letting the agent exist beyond a user's prompt needs only three additions to the kernel:

- **External events**: a new event `Trigger(source, actor, payload)` that goes through the same pipeline and the same journal as a user prompt; code filters first (allow lists, team rules), then the model judges.
- **A system identity**: whom the agent acts as when nobody asked. Always bound to one user; team-level matters go through the team wiki and administrator rules; no global agent.
- **Tasks** (done): a run can hand a piece of work to a session opened only for it and go on; the result arrives later as an event, into the current run or starting a run that no person started. This is what is usually called a subagent; here it is an ownership relation and one more kind of input event, not a new entity and not a new waiting state. See “任务” in the manual.

On screen: one main window, long work moved into "topics" automatically, externally triggered work tagged with its source, and nothing on screen while the agent is idle.

## 3. Super-Agent (designing)

Topics: `super-agent` `multi-agent`

The direction DeepSeek describes in *Exploring Super-agent Design* and the [dsh-superagent-prototype](https://github.com/deepseek-ai/dsh-superagent-prototype): an agent that listens to the team's messages, review comments and CI results, acts only when asked or allowed by a written team rule, and speaks only when someone must decide or can use a result.

- First event source: **GitHub** (issues, comments, CI results), pointed at Tantalum's own repository to take on work and open pull requests, so it validates itself.
- Allow lists, team rules, and an **observe mode** that first records what it would have taken on.
- Router dispatches, workers run in parallel: here that is tasks with a restricted tool set; the watchdog is a timer plus timeouts.
- Chat sources such as Feishu come later.

## 4. Timers and watchdog

Topics: `scheduler`

Timers as an event source: periodic reviews, the librarian's consolidation, checks that workers make real progress. The kernel already has `StartTimer`/`TimerFired`; what is missing is a timer table that survives restarts, and the UI.

## 5. Ongoing engineering

- **Hardening**: the post-0.1 whole-codebase review is fixed in five phases; every release gets another review and an [open-code-review](https://github.com/alibaba/open-code-review) scan.
- **Windows**: the server runs on Windows (as a scheduled task), verified by CI; the connector has no Windows sandbox yet.
- **Local workspace commands**: since 2026-11-14 there is no OS sandbox (the macOS-only MXC was removed); on all three systems each command is shown to the user for approval first (`connector-jar`). A cross-platform sandbox may come later.
- **Web**: `web_fetch` on the server and a browser on the user's own machine (local Playwright MCP) exist; a `web_search` API and PDF reading come later.
- **Journal format bump**: group `RunState` and `SessionState` by phase for real (views only today), together with the next format version.

## Not planned

- A global agent not bound to a user: isolation is the baseline of a multi-user foundation.
- Rewriting memories every turn: see the paper under item 1.
- A paid search API as the only option: it disappears when you switch to a local model.

## Versions

- **0.1** (2026-11-07): kernel, journal and replay, multi-user console, plugins, memory, attachments, local workspace, web, model routes, waiting for the user, Windows. See the [release](https://github.com/intelac/tantalum/releases/latest).
- **0.2**: item 1 (LLM wiki) and the first half of item 2 (external events, system identity).
- **1.0**: used in a company for real, with the Super-Agent built.
