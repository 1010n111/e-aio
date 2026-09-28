# e-aio Agent Guide

e-aio is an open-source, self-hosted modular monolith with a Java 21 + Spring Boot 4.1.x + Spring Modulith backend and a Vue 3 + Vite + Element Plus frontend; modules expose one-way APIs.

## Always

- Read [CONTEXT.md](CONTEXT.md) for project terminology and read the relevant files in [docs/adr/](docs/adr/) before exploring a behavior, interface, or data decision.
- Use the scoped authority model in [task-workflow.md](docs/agents/task-workflow.md): agent workflow lives here and in `docs/agents/`, terminology lives in `CONTEXT.md`, recorded decisions live in `docs/adr/`, and product requirements/design follow `03 > 02 > 01`.
- Keep module boundaries, dependency direction, API conventions, and error-code ranges frozen. Route changes through the issue/ADR process described in [task-workflow.md](docs/agents/task-workflow.md).
- Run backend commands from `backend/e-aio/` (Maven multi-module) and frontend commands from `frontend/` (npm).
- Before claiming completion or submitting a change, use the relevant [build and test gates](docs/agents/build-and-test.md) and [Git workflow](docs/agents/git-workflow.md).

## Task routing

- Implementation or module changes: follow [task workflow](docs/agents/task-workflow.md) and select the applicable P0-P3 batch.
- API, response, error-code, or idempotency work: [api-conventions.md](docs/agents/api-conventions.md).
- Modules, packages, dependency direction, or cross-module calls: [architecture.md](docs/agents/architecture.md).
- Java, DTO mapping, validation, logging, or common facades: [java-conventions.md](docs/agents/java-conventions.md).
- Frontend pages or request wrappers: [frontend-conventions.md](docs/agents/frontend-conventions.md).
- Tables, migrations, or field conventions: [database.md](docs/agents/database.md).
- Technology choices or new dependencies: [tech-stack.md](docs/agents/tech-stack.md).
- Issues and triage: [issue-tracker.md](docs/agents/issue-tracker.md) and [triage-labels.md](docs/agents/triage-labels.md).
- Design-doc lookup and batch entry points: [docs-map.md](docs/agents/docs-map.md).

## Required gates

- Backend local gate: from `backend/e-aio/`, run `mvn -B verify -DskipITs`; the full CI gate is `mvn -B verify` with Docker.
- Frontend gate: from `frontend/`, run `npm run lint`, `npm test`, and `npm run build` when the frontend is in scope.
- The complete matrix, environment requirements, and CI-only checks live in [build-and-test.md](docs/agents/build-and-test.md).
