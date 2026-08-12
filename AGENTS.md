# Gradion Book Illustration Studio

## Mission and sources

- Build the Gradion Intern Fullstack Developer take-home: turn book text into an art style, character portraits, and one chapter illustration with Gemini.
- The assessment specification is the source of truth. `../app-demo.html` is only a UI/behavior reference; never copy its `localStorage`, fake timers, single-tab guard, or fake stale threshold.
- Do not silently reinterpret requirements. Surface questionable assumptions and record real decisions when they happen.

## Non-negotiable behavior

- The user explicitly runs these steps in order: `STYLE`, `CHARACTERS`, `PORTRAITS`, `CHAPTERS`, `ILLUSTRATIONS`.
- Enforce on the backend: at most 2 adult characters and 1 chapter.
- Persist every successful result. Refresh, sign-out, and server restart must resume rather than restart.
- Claim a step atomically on the server. Double-clicks, refreshes, and multiple tabs must not duplicate Gemini calls.
- A failed step is retryable without changing completed steps. A stranded `RUNNING` step must have an explicit recovery path.
- Persist image progress per item so portraits/illustrations appear as each finishes.
- Upload/send the book to Gemini once, persist the returned reference/interaction IDs, and never auto-retry paid calls.
- Store book text and generated images on the local filesystem and serve them through the backend API.

## Engineering rules

- Keep one React/Vite/TypeScript frontend and one Spring Boot backend. Prefer existing, standard, or framework features over new infrastructure.
- No PostgreSQL, Redis, queues, microservices, containers, or speculative abstractions unless a measured requirement forces them.
- Validate at API boundaries, enforce project ownership on every read/write/media request, and never commit secrets.
- Inspect related code and tests before changing behavior. Write or update the smallest meaningful frontend and backend tests for every behavioral change.
- Run the relevant checks before declaring work complete. Never invent test output, AI disagreements, or assessment evidence.
- Keep commits small and meaningful. Note substantial AI authorship honestly in commit bodies.
- Do not add entries to DECISIONS.md automatically. Only propose a decision entry after a real trade-off, disagreement, correction, or architectural choice has occurred, and wait for user approval before recording it.
