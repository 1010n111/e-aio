# Task Workflow

Use this file for implementation or module changes. Documentation and local-tooling changes use the relevant topic guide directly and do not require a P0-P3 batch.

## Authority by purpose

- **Agent workflow:** `AGENTS.md` and `docs/agents/`.
- **Terminology:** root `CONTEXT.md`.
- **Recorded decisions:** `docs/adr/`; an ADR is authoritative for the decision it records.
- **Product requirements and design:** `03` detailed design takes precedence over `02` requirements, which takes precedence over `01` feasibility material.

When sources disagree within one purpose, stop and surface the conflict. Do not silently override an ADR, glossary term, or frozen contract.

## Before implementation

1. Read `CONTEXT.md` and the ADRs relevant to the behavior, interface, or data change.
2. Use [docs-map.md](docs-map.md) to identify the target module and its P0-P3 batch.
3. For the current P1 batch, start with `04-企业级一体化管理系统-e-aio-详细设计说明书-P1-1-批次总册.md`, then read the module or capability volume that owns the change.
4. Do not implement work from a later batch before the queue allows it.
5. Check the topic guide linked from [AGENTS.md](../../AGENTS.md) before editing code.

## Before submission

1. Run the smallest relevant checks from [build-and-test.md](build-and-test.md); use the full gate when the change affects a shared contract or release path.
2. Recheck frozen module boundaries, dependency direction, API rules, and error-code ranges.
3. Update the authoritative document when a terminology or recorded decision changes; do not copy that rule into a second guide.
4. Stage only files belonging to this theme and make one focused commit for the conversation's code or documentation changes. Do not create an empty commit; see [git-workflow.md](git-workflow.md).

Completion means the changed behavior or document has the relevant evidence, the authoritative source is updated, and any unresolved conflict is reported explicitly.
