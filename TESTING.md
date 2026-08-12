# Testing

The project tests the backend and frontend separately, with `npm test` as the
root command that runs both suites.

## Backend

Spring Boot tests use H2 in-memory databases and fake Gemini/image boundaries.
They cover identity validation and reuse, project ownership and book input
validation, pipeline ordering, duplicate claims, retry and stale recovery,
malformed Gemini output, portrait/chapter/illustration persistence, partial
image failure, and ownership-checked media access.

Automated tests never call Gemini, Hugging Face, or another external AI service.

## Frontend

Vitest and Testing Library cover the states that matter to the user: identity
validation and errors, empty project lists, file import/removal, project
creation loading, project detail loading, step running/error/retry/recovery,
generated style/characters/portraits/chapters/illustrations, progress display,
sign out, and browser-back navigation.

The tests focus on rendered user behavior rather than component implementation
details.

## Deliberately not tested

There is no browser E2E suite, visual snapshot suite, or automated real-provider
test.

Browser E2E is not required by the assessment, and real Gemini/Hugging Face
calls would make the test suite slower, less deterministic, and dependent on
external quota and availability. Provider integration and the complete
five-step flow were instead verified manually.

The optional happy-path integration test across all five steps was not treated
as a coverage requirement; the backend pipeline behaviors are tested through
the individual ordering, persistence, retry, and concurrency cases.

## Real test report

Run from the repository root on 2026-08-12:

```text
$ npm test

[INFO] Results:
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

> gradion-book-illustration-studio-frontend@0.0.1 test
> vitest --run

✓ src/App.test.tsx (28 tests)
Test Files  1 passed (1)
Tests       28 passed (28)