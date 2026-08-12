# Implementation plan and status

The application is implemented as small vertical slices. The current codebase contains the complete five-step pipeline.

## Completed milestones

1. **Full-stack harness** — React/Vite/TypeScript, Spring Boot, H2 file mode, health endpoint, and root start/test/build scripts.
2. **Identity** — name/email lookup, HttpOnly session cookie, refresh persistence, and sign out.
3. **Project persistence** — owned list/detail, title plus exactly one pasted/uploaded book source, local `book.txt`.
4. **Pipeline state machine** — five ordered persistent steps, atomic claims, duplicate prevention, retry, run tokens, and previous-instance stale recovery.
5. **Gemini text** — STYLE, CHARACTERS, and CHAPTERS through persisted File/Interactions context. Validation enforces two adult characters maximum and exactly one chapter.
6. **Hugging Face images** — PORTRAITS and ILLUSTRATIONS through a mockable image boundary using Nscale FLUX text-to-image.
7. **Project detail/media** — server-driven stepper, polling, per-item portrait state, chapter metadata, illustration metadata, and ownership-checked media endpoints.

## Pipeline

```text
STYLE -> CHARACTERS -> PORTRAITS -> CHAPTERS -> ILLUSTRATIONS
```

Every step requires explicit user action. Failed steps are retryable without changing earlier outputs. External calls are not automatically retried. Automated tests use fake gateways and do not call Gemini or Hugging Face.

## Current limits

- No password/OAuth authentication.
- No queue, worker, scheduler, SSE, or WebSocket.
- No automatic retries.
- The configured Nscale image endpoint is text-to-image only. Illustration prompts reuse persisted style and character appearance descriptions; portrait bytes are not sent as reference images.
- No pipeline steps beyond ILLUSTRATIONS.

## Verification

```bash
npm test
npm run build
```

On Windows, a locked file in `frontend/dist` can make root build cleanup fail. Use `npx vite build --emptyOutDir=false` from `frontend` to verify frontend compilation without cleanup.
