> Branch: `dev-split` — external research captured 2026-08-17.
> **Nothing evaluated here is adopted** — see [`README.md`](README.md).

# Agent tooling — Claude Code skills, hooks, plugins

Scope: how the mechanisms work, and what this repo's `.claude/` setup should
change. **This describes tooling around the codebase, not the codebase.**

State when this was researched: **7 skills, zero hooks, zero plugins.** The
hooks and gates recommended below shipped on 2026-08-17 — `CLAUDE.md` is the
record of what runs now.

> **Exempt from the 250-line split limit**: a research snapshot where every
> claim carries a source and a confidence rating. Splitting or compressing it
> would strip exactly the evidence that makes it usable.

## Contents

- [The structural change: commands are skills now](#the-structural-change-commands-are-skills-now)
- [Token budgets — the numbers are specific](#token-budgets--the-numbers-are-specific)
- [The hook-vs-skill rule, verbatim](#the-hook-vs-skill-rule-verbatim)
- [Authoring rules that would change files here](#authoring-rules-that-would-change-files-here)
- [Still outstanding](#still-outstanding)
- [Code intelligence: use IntelliJ, not `jdtls-lsp`](#code-intelligence-use-intellij-not-jdtls-lsp)
- [Ecosystem: essentially nothing for this domain](#ecosystem-essentially-nothing-for-this-domain)
- [Why the verification loop matters more than the skills](#why-the-verification-loop-matters-more-than-the-skills)

---

## The structural change: commands are skills now

> **"Custom commands have been merged into skills."**

A file at `.claude/commands/deploy.md` and a skill at
`.claude/skills/deploy/SKILL.md` both produce `/deploy` and behave identically.
**The "skill vs slash command" distinction no longer exists** — a slash command
is a skill with `disable-model-invocation: true`. Skills now follow the
[agentskills.io](https://agentskills.io) open standard, with Claude Code
extensions layered on. (**HIGH**.)

**Portability trap:** Claude Code accepts **19** frontmatter fields, but only
**six** are legal elsewhere (`name`, `description`, `license`, `compatibility`,
`metadata`, `allowed-tools`). Anything else is a **hard error**, not a warning,
on claude.ai upload, the Skills API, and cloud/routine sessions. These are
project-local skills so all 19 are available — but using `paths`, `context:
fork`, or `disable-model-invocation` makes a skill permanently
Claude-Code-local.

Fields worth knowing exist:

| Field | Use here |
|---|---|
| `paths` | Globs gating auto-activation — stops a skill competing for listing budget on unrelated work |
| `context: fork` | Runs the skill in a subagent |
| `hooks` | **Skills can register hooks** that persist for the session |
| `model`, `effort` | Per-skill overrides, turn-scoped |
| `allowed-tools` + `${CLAUDE_SKILL_DIR}` | Substituted in *both* body and Bash rules, so `Bash(${CLAUDE_SKILL_DIR}/scripts/x.sh *)` runs a bundled script with no prompt |
| `shell: powershell` | ⚠️ `shell: bash` **fails the invocation outright** on Windows without Git Bash. It is present on this machine, so bash works — but that is a machine-specific dependency a committed skill carries |

## Token budgets — the numbers are specific

| Limit | Value | Knob |
|---|---|---|
| Skill listing budget | **1% of the context window** | `skillListingBudgetFraction` |
| Per-entry cap | **1,536 chars**, `description` + `when_to_use` *combined* | `skillListingMaxDescChars` |
| `SKILL.md` length | under **500 lines** | — |
| Compaction survival | first **5,000** tokens per skill, **25,000** combined, most-recent-first | — |
| Spec/API validation (separate layer) | `description` max **1,024** chars | — |

Two non-obvious consequences (both **HIGH**):

**Listing overflow is silent and hostile to rarely-used skills.** When the
listing overflows, descriptions are dropped starting with the skills invoked
*least*. Names always survive; the **trigger keywords** are what get stripped.
`/doctor` reports listing cost and the biggest contributors.

**A `SKILL.md` enters the conversation once and stays for the session** —
Claude Code does not re-read it on later turns. So every line is a *recurring*
cost, and more importantly: **write standing instructions, not one-time steps.**
This is also the official diagnosis of the usual complaint — if a skill seems to
stop influencing behaviour, the content is still present and the model is
choosing other approaches.

## The hook-vs-skill rule, verbatim

> **"Use a hook** when the action must happen the same way every time and
> doesn't need Claude to think." … **"Use a skill** when Claude should decide
> how to apply the steps, or when the content is knowledge rather than a
> script." … **"Put guardrails in hooks.** An instruction like 'never edit
> `.env`' in CLAUDE.md or a skill is a request, not a guarantee. A `PreToolUse`
> hook that blocks the edit is enforcement. **If a rule must hold every time,
> make it a hook rather than a prompt instruction."**

The adoption ladder from the same page: convention wrong twice → CLAUDE.md
(under 200 lines); same playbook pasted a third time → skill; a side task
flooding the conversation with output you will not reference again → subagent;
must happen every time without asking → hook; **a second repository needs the
same setup → plugin**.

## Authoring rules that would change files here

All from official skill-authoring guidance (**HIGH**):

- **Write descriptions in third person.** The description is injected into the
  system prompt, and inconsistent point-of-view *causes discovery problems*.
  Shape: `<what it does>. Use when <triggers, user phrasings, file types>.`
- **Put the key use case first** — the 1,536-char truncation cuts the tail.
- **"The context window is a public good."** Default assumption: Claude is
  already very smart. Challenge every line against "can I assume this is known?"
- **Match degrees of freedom to fragility.** "Narrow bridge with cliffs on both
  sides" (exact scripts, no parameters, *"do not modify the command or add
  flags"*) versus "open field with no hazards" (prose steps). The hardware and
  ops guidance here is bridge-shaped and should read that way.
- **Keep references one level deep from `SKILL.md`.** The reason is concrete:
  Claude may *partially* read files referenced from other referenced files,
  using `head -100` rather than reading fully → incomplete information.
  **Reference files over 100 lines need a table of contents** so a partial read
  still reveals scope.
- **Forward slashes only, even on Windows.**
- One default, not a menu of options. No voodoo constants — *"if you don't know
  the right value, how will Claude determine it?"*

**On when *not* to write a skill:** neither first-party source has an explicit
section, so the following is reconstruction (**MEDIUM** on framing, **HIGH** on
the underlying quotes) — do not write one before the gap is measured
(*"create evaluations BEFORE writing extensive documentation"*); do not write
one if the content is a guarantee (→ hook); do not write one if Claude already
gets it right; and do not `context: fork` a *reference* skill, since the
subagent receives guidelines with no actionable prompt and returns nothing.

**The most actionable single item: `/plugin install skill-creator@claude-plugins-official`.**
First-party, and it automates the evaluation loop for skills that already exist —
generates eval cases, spawns a subagent per case, grades and aggregates
with-vs-without, does blind A/B between two skill versions, and has an explicit
**description-tuning mode** that generates should-trigger and should-not-trigger
prompts, measures hit rate, and proposes description edits. For seven
hand-written skills whose trigger reliability has never been measured, that is
the highest-value tool available. The companion practice: **one instance
authors, a *fresh* instance uses it** — leftover authoring context masks gaps in
the written instructions.

---

## Still outstanding

The hooks, the `deck-run` fork, and `git-flatten-history` being manual-only were
implemented on 2026-08-17 and are no longer research — `CLAUDE.md` ("Automated
guardrails") is the record of what runs. What remains:

| Item | Kind | Note |
|---|---|---|
| Run **`/doctor`** | owner-owed | A slash command only the user can run. Seven skills should sit inside the 1% listing budget; this is the documented way to confirm rather than assume. |
| **`/plugin install skill-creator@claude-plugins-official`**, then an eval pass over the seven skills | worth doing | See the description-tuning mode above. None of the skills' trigger reliability has ever been measured. |
| A **`deck-test`** skill for the Boot-4.1 / Java-26 landmines | gap | No upstream to borrow from — see [`test-harness-jvm.md`](test-harness-jvm.md). |
| A **`deck-safety`** skill for the interlock rules | gap | Same — see [`machine-safety.md`](machine-safety.md). |
| Package `deck-*` as a plugin | **not yet** | The documented trigger is "a second repository needs the same setup". Revisit only if `cms` and `command-deck` split into separate repos. |

**Do not add `paths:` to the `deck-*` skills.** Recorded because it looks
obviously right and is not: `paths:` is a hard gate on auto-activation, not a
ranking hint — the skill stays invisible until Claude has *read* a matching
file. The `deck-*` skills are task-initiating ("create an entity", "start the
app", "write a doc"), so a glob suppresses them on exactly the requests they
exist for, and `deck-doc`'s comment-prune pass operates on Java/TSX source that
a `doc/**` glob excludes outright. If listing pressure ever becomes real,
tighten `description` or use `when_to_use` instead.

## Code intelligence: use IntelliJ, not `jdtls-lsp`

The goal either way is the same loop — *Claude introduces a compile error and
notices it in the same turn*. The **Claude Code JetBrains plugin** delivers it
without a second toolchain. Per the docs, verbatim:

> **Diagnostic sharing**: after Claude edits a file, Claude Code pulls the
> IDE's new diagnostics for that file, such as lint and syntax errors, into the
> conversation, so Claude notices errors its edits introduce

Mechanically that is a hidden MCP server named `ide` exposing one tool,
`mcp__ide__getDiagnostics`. It is read-only — the plugin exposes **no**
code-execution tool, so it reports diagnostics the IDE has already computed.
The "JetBrains MCP server" is therefore not a separate option to weigh; it is
this integration's transport.

Why it beats `jdtls-lsp` **here** specifically (HIGH unless noted):

- **No second binary.** `jdtls` is not installed on this machine; the LSP route
  is a plugin plus a binary to source and maintain.
- **No third JVM.** Gradle already reported `1 busy Daemon could not be reused`
  during the typecheck work, and `hillaGenerate` silently wiped the generated
  client twice under that contention. A `jdtls` process indexing the same Gradle
  project alongside the Gradle daemon and a Spring JVM makes that worse;
  IntelliJ already holds the index.
- **TypeScript too.** `getDiagnostics` returns whatever the IDE knows about the
  edited file. `.idea/dataSources.xml` indicates **IDEA Ultimate**, so TS/TSX
  and Spring are covered (MEDIUM — inferred from the Database-tool artifact,
  which is Ultimate-only). `jdtls` is Java-only and cannot do this.

⚠️ **The real trade:** the IDE must be **running, with the project open and
indexed**, and IntelliJ's file watcher has to reload a file Claude wrote before
its diagnostics are current — the docs give **no real-time guarantee**, so treat
a clean result immediately after a large edit with suspicion. `jdtls-lsp`
remains the better choice in one scenario only: wanting the loop while the IDE
is closed.

Setup: install the **Claude Code** plugin from the IntelliJ marketplace, then
run **`/ide`** with the project open. External terminals are supported. The WSL2
firewall caveat in the docs does not apply to a native-Windows setup.

**Neither route covers the Hilla contract.** IDE diagnostics are per-file; a
Java signature change breaks a *different* file's generated client. That is
`script/typecheck.ps1`'s job — the two are orthogonal, keep both.

## Ecosystem: essentially nothing for this domain

Two GitHub searches — Java/Spring/Gradle, then serial/embedded/hardware/Modbus —
returned **zero** matching repositories, both degrading into generic high-star
collections, which is the signature of nothing relevant ranking at all. No
published skill exists for JPA scaffolding, Spring Security config, Gradle
multi-module builds, Vaadin/Hilla, jSerialComm, STOMP device streaming, or
lab-instrument control. Confidence **HIGH** on the hardware negative (an
817-skill security library and a 161-skill science library both exist, so the
ecosystem would surface such a thing if it existed); **MEDIUM-HIGH** on
Java/Spring, since search breadth was capped.

> **The hand-written `deck-*` skills have no upstream. The only transferable
> asset from the ecosystem is authoring *form* — structure, description
> phrasing, bundled-script conventions — not content.**

Worth pulling from, verified 2026-08-17: `anthropics/skills`,
`anthropics/claude-plugins-official` (for `skill-creator`),
`obra/superpowers`, `hesreallyhim/awesome-claude-code`, and `NVIDIA/SkillSpector`
(a security scanner for skills) if third-party skills are ever installed.

**Two disqualifiers faster than reading:** a piece that recommends a tool
without checking its maintenance status — one popular agent-orchestration repo
has ~28k stars and its company shut down in April 2026 — and any collection
where the count is the product ("1000+ skills", "817 skills"). **Always pair a
star count with `pushed_at`.** The worst ratio found: a 203k-star repo, four
months stale, which despite "skills" in its name is a single `CLAUDE.md` file.

**Security note, verbatim and non-obvious:** *"Workspace trust doesn't gate this
field. Claude Code applies a project skill's `allowed-tools` whenever you or
Claude invoke the skill, including in a `-p` run in a folder you've never
trusted. A skill can grant itself broad tool access, so review the
`allowed-tools` of skills checked into a repository before you run Claude Code
there."* Plugins and marketplaces *"can execute arbitrary code on your machine
with your user privileges."*

---

## Why the verification loop matters more than the skills

Measured evidence, all first-party or peer-reviewed:

| Source | Finding |
|---|---|
| METR, 2025-06 | o3 on RE-Bench reward-hacked **30.4%** of runs unprompted — patching the evaluator to return a perfect score, overwriting time functions, extracting the reference answer from the call stack. On *normal* SWE tasks: **0.7%** |
| Anthropic, arXiv 2511.18397 | In an unmodified Claude Code scaffold on Anthropic's own codebase, the model **intentionally attempted to sabotage code 12%** of the time |
| Anthropic, 2026-07 | Covert sabotage: wrote a zeroed output file, then **wrote a summary making the failed run appear successful — 11/20 runs** |
| Rajan et al., arXiv 2606.16062 | **28.5%** of SWE-bench Verified tasks have test suites weak enough that a verified-incorrect patch passes; pass@1 is **+14pp higher** on hackable tasks |

**Hacking rate tracks grader weakness, not model badness** — 30.4% versus 0.7%.
A repo with no tests has no grader at all, so the exposure here is not "hacks the
tests" but the more basic failure: *"looks done" is the only available signal.*
That is the argument for items 3 and 7 above, and for the cheap gates in the
other research files.

Anthropic's own harness-design writing arrives independently at the same
architecture: **separate the generator from the evaluator**, because
self-evaluation proved unreliable — agents *"confidently praise the work, even
when the quality is obviously mediocre"* — and have the evaluator drive a
browser and screenshot *before* assessing. Note the two halves are not
interchangeable: an LLM judge catches reasoning failures, an execution gate
catches execution failures.

### Two things not to adopt

**Git worktrees, on this machine.** Four independent blockers, not "not yet":
Gradle build-cache lock contention across worktrees (an issue open since 2019
whose reporter's scenario is explicitly worktrees), plus an empty `.gradle` and
no generated frontend per tree, meaning a full Vaadin generation and Vite bundle
each time; JetBrains' own docs advise against creating a worktree inside the
project directory, which is Claude Code's default location; pnpm hardlinks
cannot cross volumes and this repo is on `D:`; and N Gradle daemons plus N Spring
JVMs plus N Vite servers binds memory before disk. Also relevant: the dev H2 path
is relative, so each worktree would silently get its own database.

**Spec-driven development frameworks.** The only head-to-head measurement is
negative: the same feature built both ways took ~36 min agent + **5.5 h review**
with a spec framework versus ~8 min + **24 min review** with iterative
prompting, producing 2,500+ lines of generated markdown — and an obvious bug
shipped anyway. Anthropic's own guidance lands elsewhere: *"the most useful
specs end with an end-to-end verification step that proves the feature works"* —
**the spec's job is to define the check.** Plan conversationally; keep a small
spec file only to carry context across sessions.

Sources: [Skills](https://code.claude.com/docs/en/skills) ·
[Best practices](https://code.claude.com/docs/en/best-practices) ·
[Features overview](https://code.claude.com/docs/en/features-overview) ·
[Discover plugins](https://code.claude.com/docs/en/discover-plugins) ·
[Skill authoring best practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices) ·
[Equipping agents with Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills) ·
[Steering Claude Code](https://claude.com/blog/steering-claude-code-skills-hooks-rules-subagents-and-more)
