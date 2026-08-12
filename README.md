# Gradion Book Illustration Studio

Local full-stack app that turns book text into a resumable illustration plan:

```text
STYLE -> CHARACTERS -> PORTRAITS -> CHAPTERS -> ILLUSTRATIONS
```

## Current scope

- React/Vite/TypeScript frontend and Spring Boot/Java backend.
- Name/email identity with an HttpOnly session cookie.
- Owned projects created from pasted text or one non-empty `.txt` file.
- H2 file-mode metadata plus local book/image files.
- Persistent ordered pipeline with atomic claims, duplicate prevention, retry, and previous-instance stale recovery.
- Gemini text for STYLE, CHARACTERS, and CHAPTERS.
- Hugging Face image generation for PORTRAITS and ILLUSTRATIONS.
- At most two adult characters and exactly one chapter.
- Ownership-checked portrait and illustration media endpoints.

There are no passwords, OAuth, queues, workers, schedulers, SSE, WebSockets, or automatic retries. Gemini is used for text generation. Image generation is selected with `IMAGE_PROVIDER=gemini` or `IMAGE_PROVIDER=huggingface`.

## Prerequisites

- Node.js 20+ and npm
- Java 17+ (tested with Java 23)
- Maven 3.9+
- Gemini API key
- Hugging Face token with Inference Providers permission

## Configuration

Copy `.env.example` to `.env` at the repository root:

```env
GEMINI_API_KEY=your_gemini_key
GEMINI_MODEL=gemini-2.5-flash-lite
GEMINI_IMAGE_MODEL=gemini-2.5-flash-image
IMAGE_PROVIDER=huggingface
HF_TOKEN=your_huggingface_token
HF_PROVIDER=nscale
HF_IMAGE_MODEL=black-forest-labs/FLUX.1-schnell
```

`IMAGE_PROVIDER=gemini` uses `POST /v1/models/{GEMINI_IMAGE_MODEL}:generateContent`
with the `x-goog-api-key` header and `responseModalities: ["TEXT", "IMAGE"]`.
The default `GEMINI_IMAGE_MODEL` is `gemini-2.5-flash-image` (Nano Banana), and
the gateway reads the returned `inlineData` bytes. `IMAGE_PROVIDER=huggingface`
uses the configured FLUX Inference Provider. The existing image gateway contract is unchanged, so
PORTRAITS and ILLUSTRATIONS keep their current persistence and retry behavior.
Illustration prompts currently include the persisted style and character
appearance descriptions; portrait image bytes are not sent as remote references.

`npm run start` loads `.env` and passes it to both applications. Never commit `.env` or keys.

## Start

```bash
npm install
npm run start
```

The backend runs on `http://localhost:8080`; Vite prints its development URL. Stop with `Ctrl+C` in the start terminal. If Windows reports `EPERM` for a Vite cache or `frontend/dist`, stop remaining Node/Java processes and rerun.

## Tests and build

```bash
# all backend and frontend tests
npm test

# production build
npm run build
```

Individual suites are `cd backend && mvn test` and `cd frontend && npm test`. If a Windows file lock prevents Vite cleanup, verify frontend compilation with `cd frontend && npx vite build --emptyOutDir=false`.

Tests use fake Gemini/image gateways and never call external providers.

## API overview

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST`/`GET`/`DELETE` | `/api/session` | Create/load, read, and sign out identity |
| `GET` | `/api/projects` | List owned projects |
| `POST` | `/api/projects` | Create with title plus pasted text or `.txt` |
| `GET` | `/api/projects/{id}` | Owned detail, steps, characters, chapter, illustration |
| `POST` | `/api/projects/{id}/steps/{step}/run` | Run current step |
| `POST` | `/api/projects/{id}/steps/{step}/recover` | Recover eligible stale run |
| `GET` | `/api/projects/{id}/media/{characterId}` | Portrait bytes |
| `GET` | `/api/projects/{id}/illustrations/{chapterId}` | Illustration bytes |

Project creation enforces exactly one valid book source.

## Persistence

```text
data/gradion.mv.db
data/projects/<project-id>/book.txt
data/projects/<project-id>/portraits/<character-id>.png
data/projects/<project-id>/illustrations/<chapter-id>.png
```

H2 stores metadata, state, Gemini references, and image metadata. Files use temporary writes and atomic moves; filesystem paths are never exposed to the frontend.

## Manual flow

1. Sign in and create a project.
2. Run STYLE, optionally supplying style in Project Detail.
3. Run CHARACTERS, then PORTRAITS.
4. Run CHAPTERS and confirm one title/prompt.
5. Run ILLUSTRATIONS and confirm the final image.
6. Refresh and restart the backend to verify persistence.

Provider failures become `FAILED` and require explicit Retry.

See [docs/plan.md](docs/plan.md) and [docs/architecture.md](docs/architecture.md) for implementation details. `DECISIONS.md` records project decisions and is intentionally not changed by documentation updates.
