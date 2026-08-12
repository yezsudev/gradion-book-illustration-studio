# Implementation Plan

This plan delivers thin end-to-end slices early. Each milestone should leave the repository runnable and reviewable; later milestones replace fakes at a narrow Gemini boundary instead of reshaping the application.

**Precondition before application code:** Run steps 1-5 of Google's “Illustrate a book: The Wind in the Willows” notebook in Colab, inspect the real prompts/responses and quota behavior, and save only genuine AI artifacts that result. Reading the notebook source during planning does not replace this assessment requirement.

## 1. Repository and feedback harness

**Goal:** Bootstrap a minimal React/Vite/TypeScript frontend and Spring Boot backend with one root start command and one root test command.

**Likely files:** `package.json`, `scripts/dev.mjs`, `scripts/test.mjs`, `frontend/`, `backend/pom.xml`, `backend/src/main/`, `.gitignore`, `.env.example`, `README.md`.

**Acceptance criteria:** One command starts both apps; the frontend can call a backend health endpoint; secrets and `data/` are ignored; no Docker or external service is needed.

**Important tests:** One Spring context/health test and one frontend smoke test run from the root test command.

**Commit boundary:** `chore: bootstrap frontend backend and test harness`

## 2. Identity vertical slice

**Goal:** Let a person enter name and email, resume the same identity by email, and sign out.

**Likely files:** backend user/session model, repository, service and controller; frontend API client, session context, identity screen and route guard.

**Acceptance criteria:** Valid identity input upserts a user by normalized email; the selected session mechanism survives refresh; sign-out clears it; invalid inputs fail at both boundaries.

**Important tests:** Backend create-versus-load and validation tests; frontend identity validation, submitting, and error-state tests.

**Commit boundary:** `feat: add lightweight identity flow`

## 3. Project persistence vertical slice

**Goal:** Create, list, and open owned projects using pasted text or a `.txt` upload.

**Likely files:** project model/repository/service/controller and filesystem store; frontend project list, new-project form, detail shell, routing and API types.

**Acceptance criteria:** A user sees only their projects; each list item has title, date, Draft status, and 0/5 progress; empty/loading/error states exist; full book text remains readable; book content is stored under the project directory, not in browser storage.

**Important tests:** Backend ownership, pasted/uploaded text, file-type, missing-input, and persistence-after-restart tests; frontend empty list, failed load, upload/paste validation, and readable book-text tests.

**Commit boundary:** `feat: persist and display user projects`

## 4. Pipeline state and atomic claiming

**Goal:** Establish the five persistent step records and the only service method allowed to claim, complete, fail, or recover work.

**Likely files:** pipeline enums/model/repository/service, schema initialization, project DTO mapping, focused service integration tests.

**Acceptance criteria:** Only the first incomplete step can be claimed; a transaction serializes claims per project; one of two concurrent claims wins; completed steps cannot rerun; failures retain prior outputs and retry only the same step; recovery changes a stale run to retryable without starting Gemini.

**Important tests:** Ordering, duplicate claim, two-thread concurrency, completion token, failure/retry, stale threshold, and derived Draft/In progress/Done status tests.

**Commit boundary:** `feat: enforce persistent pipeline state transitions`

## 5. Fake pipeline end to end

**Goal:** Wire the project detail stepper and action panel to the real backend state before spending API quota.

**Likely files:** pipeline controller and fake `GeminiGateway`; frontend project detail, stepper, polling hook, action/error/recovery panels.

**Acceptance criteria:** A user explicitly advances five ordered steps; refresh and a second tab show the same `RUNNING` state; duplicate requests receive the existing state/conflict; the UI names the running step and exposes retry/recovery states.

**Important tests:** Backend happy-path integration with fake outputs; frontend current/pending/done stepper, running, failed, and stale recovery states.

**Commit boundary:** `feat: connect resumable pipeline flow with fake Gemini`

## 6. Gemini book context and style

**Goal:** Replace the fake style call with the notebook's File API plus chained Interactions flow.

**Likely files:** REST Gemini gateway, HTTP configuration, Gemini DTOs, style handler, project Gemini-reference fields, `.env.example` and integration fixtures.

**Acceptance criteria:** The first Style attempt uploads the saved book once and persists the file/interaction references before asking for a generated or user-supplied style; retries reuse persisted references; there is no automatic HTTP retry; API keys remain server-side.

**Important tests:** HTTP fixture tests for upload/chaining, custom/generated style, reference reuse after failure, malformed/failed Gemini responses, and no automatic retry.

**Commit boundary:** `feat: generate style from persisted Gemini context`

## 7. Structured adult characters

**Goal:** Generate and persist up to two adult character prompts from the text interaction chain.

**Likely files:** character model/repository/DTO, JSON schema and character step handler; frontend character cards.

**Acceptance criteria:** The request asks for adults only and structured `name`/`prompt` output; schema and server validation enforce at most two; malformed or oversized output fails safely or is bounded before persistence; character cards survive refresh.

**Important tests:** Valid structured output, child/invalid entry handling policy, hard cap, malformed JSON, ordering guard, and character-card states.

**Commit boundary:** `feat: generate bounded adult character prompts`

## 8. Portrait generation with item progress

**Goal:** Generate one portrait per persisted character and expose each result immediately.

**Likely files:** media filesystem store, image interaction DTO/handler, character image fields, authorized media endpoint; frontend portrait cards and polling.

**Acceptance criteria:** At most two image calls run sequentially; every completed image is atomically written then recorded; polling shows each portrait land; retry skips already completed portraits; files are served only to the owning user.

**Important tests:** Per-item persistence, partial failure and resume, two-image cap, safe file paths, media ownership, and frontend partial-progress rendering.

**Commit boundary:** `feat: generate and persist character portraits`

## 9. Chapter prompt and consistent illustration

**Goal:** Complete the notebook pipeline with one structured chapter prompt and one scene image reusing portrait context.

**Likely files:** chapter model/repository/DTO, chapter and illustration handlers; frontend chapter card and illustration state.

**Acceptance criteria:** The server persists at most one chapter; its prompt references named characters; the image request chains from portrait interactions or supplies their saved portraits; the finished project derives Done status and remains reopenable.

**Important tests:** One-chapter cap, character-reference request, image persistence, failed illustration retry, full fake-Gemini five-step integration, and completed UI state.

**Commit boundary:** `feat: generate chapter prompt and illustration`

## 10. Failure, concurrency, and UX hardening

**Goal:** Exercise the ugly paths with realistic latency and make the UI accessible and stable.

**Likely files:** pipeline/controller error mapping, stale recovery rules, frontend status copy/focus behavior/responsive styles, existing test suites.

**Acceptance criteria:** Double-click, refresh, multi-tab, Gemini error, malformed response, partial image completion, client disconnect, and simulated restart all have verified outcomes; polling stops at terminal state; keyboard focus, labels, reduced motion, and responsive layouts meet the demo's floor.

**Important tests:** Targeted concurrency/restart integration tests and frontend loading/error/empty/reduced-motion checks; manual two-tab and server-kill checklist.

**Commit boundary:** `fix: harden pipeline recovery and user feedback`

## 11. Final evidence and handoff

**Goal:** Make a reviewer able to start, test, understand, and evaluate the real work without guesswork.

**Likely files:** `README.md`, `DECISIONS.md`, `TESTING.md`, saved prompts/AI artifacts, start/test scripts.

**Acceptance criteria:** README documents prerequisites, exact commands, env vars, filesystem data, and architecture; DECISIONS contains only decisions that actually occurred with at least three genuine AI overrides; TESTING explains scope and contains pasted output from a fresh real run; no secret, generated data, placeholder, or bonus feature slipped in.

**Important tests:** Run the one-command suite from a clean checkout, paste its exact output, then complete a manual happy path with a quota-aware Gemini key.

**Commit boundary:** `docs: finalize assessment evidence and handoff`
