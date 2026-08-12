# Gemini Style and Characters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run only STYLE and CHARACTERS through persisted Gemini File/Interactions context while preserving the existing pipeline state machine.

**Architecture:** A small mockable `GeminiGateway` performs File API and stable Interactions HTTP calls. `PipelineService` retains all claims and dispatches only the two supported keys through a context-aware handler; H2 stores local outputs and expiring remote references.

**Tech Stack:** Spring Boot, H2, JDK `HttpClient`, React/Vite/TypeScript, JUnit, Vitest.

## Global Constraints

- Do not modify `DECISIONS.md`.
- Use `/v1/interactions` for stable Interactions v1; use `/upload/v1beta/files` only because the current File API documentation requires it.
- No real Gemini calls in automated tests, no automatic retries, and no work beyond STYLE/CHARACTERS.

---

### Task 1: Persist outputs and remote context

**Files:** schema, `ProjectController`, `PipelineService`, backend controller tests.

- [x] Write failing detail-response tests for nullable style and empty character collection.
- [x] Add H2 columns/tables for Gemini file/interaction references, style, and character name/prompt/position.
- [x] Extend owned detail query and DTO without changing project creation.
- [x] Run the focused controller tests and confirm they pass.

### Task 2: Testable Gemini REST boundary

**Files:** create `GeminiGateway`, `HttpGeminiGateway`; backend gateway/pipeline tests; properties and `.env.example`.

- [x] Write failing unit tests using a gateway double for style and structured character responses.
- [x] Implement resumable File API upload using local `book.txt`; implement stable Interaction creation, retrieval, and JSON response extraction with JDK `HttpClient`.
- [x] Configure key/model/base URL only through server environment properties; do not expose the key to frontend.
- [x] Run focused gateway tests and confirm they pass without network.

### Task 3: Run STYLE through the existing claim flow

**Files:** `PipelineService`, `PipelineController`, tests.

- [x] Write failing tests: generated style is persisted; supplied style completes without calling Gemini; failed generation makes STYLE retryable.
- [x] Accept optional JSON style only for `STYLE`; after claim, persist supplied input or ensure/rebuild book context, invoke gateway, persist output/reference, and finish with the existing token guard.
- [x] Run focused pipeline tests and confirm state/order/retry still pass.

### Task 4: Run CHARACTERS with strict structured validation

**Files:** `PipelineService`, `GeminiGateway`, tests.

- [x] Write failing tests: one/two valid adult entries persist; malformed, child, blank, zero, or over-two entries fail; retry can succeed; two concurrent requests call gateway once.
- [x] Ensure/rebuild compatible book-plus-style context before the one structured call; validate then atomically replace persisted character rows before tokened completion.
- [x] Run focused pipeline/concurrency tests and confirm they pass.

### Task 5: Detail UI and regression coverage

**Files:** `frontend/src/App.tsx`, `frontend/src/api.ts`, `frontend/src/App.test.tsx`, CSS only if needed.

- [x] Write failing frontend tests for the optional STYLE input, stored style, and persisted character cards.
- [x] Send optional style only in STYLE run requests; render returned style/cards while retaining current running/failed/retry UI.
- [x] Run full frontend tests and build.

### Task 6: Final verification

**Files:** no documentation changes unless implementation reveals a factual correction.

- [x] Run `npm test` and `npm run build` at repository root.
- [x] Report actual outputs, including any Windows lock or network issue; do not commit.
