# Automation Studio AI Project Memory

> Last updated: 2026-07-13

## Purpose

The `.ai` directory is durable project memory for ChatGPT, Codex, Copilot, and other AI-assisted engineering tools. It summarizes the product, architecture, active story, decisions, backlog, and prior work so that a new session can recover context without guessing.

This directory supplements, and does not replace, the detailed documentation under [`docs/`](../docs/).

## Required Reading

Before making changes, an AI tool should read:

1. [`context.md`](context.md)
2. [`current-story.md`](current-story.md)
3. [`architecture-summary.md`](architecture-summary.md)
4. [`decisions.md`](decisions.md)
5. The architecture documents linked from `architecture-summary.md`
6. The relevant development log under [`docs/development-log/`](../docs/development-log/)

Use the appropriate reusable role prompt in [`prompts/`](prompts/) when useful.

## Maintenance Rules

- Treat repository contents and Git history as the primary source of truth.
- Verify the active branch, working tree, implementation, tests, and documentation before updating memory.
- Clearly label work as **Completed**, **In progress**, **Planned**, or **Blocked**.
- Never mark a story complete until its Definition of Done is verified.
- Append durable history to `session-log.md`; do not erase earlier facts merely because priorities change.
- Update `current-story.md` when scope, status, blockers, or the next action changes.
- Record only supported architectural decisions in `decisions.md` and the ADRs.
- Keep summaries concise and link to detailed repository documents.
- Never store passwords, tokens, private credentials, or resolved secret values.

