# Gradion Book Illustration Studio

Milestone 1 of the Gradion intern full-stack assessment: a small React/Vite frontend talking to a Spring Boot backend through a health endpoint. The frontend shell follows the visual language and five-step preview from `../app-demo.html`; the demo's browser-only persistence and fake timers are not used.

## Prerequisites

- Node.js 20+ and npm
- JDK 17+ with Maven 3.9+

## Start locally

From this directory:

```text
npm run start
```

The frontend runs at `http://localhost:5173` and proxies `/api` to the backend at `http://localhost:8080`. Stop both processes with Ctrl+C.

## Test and build

```text
npm test
npm run build
```

`npm test` runs the Spring Boot tests and Vitest suite. `npm run build` packages the backend without tests and builds the frontend.

## Configuration and data

Copy `.env.example` to `.env` when local overrides are needed. Gemini is not called in this milestone, so `GEMINI_API_KEY` is intentionally unused.

The backend is configured for file-mode H2 at `./data/gradion` relative to its working directory. The database and generated build files are ignored by Git.

## Structure

- `frontend/`: React, Vite, TypeScript, and the health-state UI/test.
- `backend/`: Spring Boot application, H2 configuration, and the `/api/health` test.
- `scripts/`: Node standard-library orchestration for start, test, and build.
