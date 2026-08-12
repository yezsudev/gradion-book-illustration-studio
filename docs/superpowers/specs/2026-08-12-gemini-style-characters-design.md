# Gemini Style and Characters Design

## Scope

Replace fake execution only for `STYLE` and `CHARACTERS`. The existing project-owned persistent pipeline claim, completion, failure, retry, stale recovery, and duplicate-call prevention remain unchanged. `PORTRAITS`, `CHAPTERS`, and `ILLUSTRATIONS` continue using the fake executor.

## Remote boundary and context

`GeminiGateway` is a small package-private boundary implemented with `java.net.http.HttpClient` and replaced by a test double in tests. Interactions use stable `/v1/interactions`; the File API remains `/upload/v1beta/files` because the current official File API documentation specifically exposes that upload endpoint.

Before any user-triggered Gemini step, the service verifies its saved remote file and interaction references. If they are unavailable or expired, it re-uploads the local `book.txt` and rebuilds the smallest context from durable local data: the book file, then the completed style when present. This happens only within an explicit user action; there are no HTTP automatic retries. Remote references are therefore cache-like, not permanent.

The database stores the Gemini file name/URI, root interaction ID, latest compatible interaction ID, and the successful generated or supplied style. References are saved immediately after each successful remote call. File API storage expires after 48 hours; interaction retention varies by tier, so both are checked before reuse.

## Step behavior

`STYLE` accepts optional JSON `style` in its existing run action. If supplied, it makes no Gemini generation call: the value is persisted and the step completes after its normal atomic claim. If absent, the backend ensures remote book context, requests a concise visual style, saves the returned text and interaction ID, then completes. A failed attempt persists no style.

`CHARACTERS` ensures a context that includes the persisted style, then requests `application/json` structured output with a schema capped at two items. The response has `name`, `prompt`, and transient `adult` fields. Server validation requires exactly one or two items, `adult=true`, nonblank name and prompt, and distinct positions. Only name and prompt are persisted. Any transport, parse, schema, or semantic validation failure changes the claimed step to `FAILED`; a normal explicit Retry can later succeed.

## API and UI

The project detail response adds nullable `style` and `characters`. The frontend displays a style textarea only while STYLE is actionable, sends it in the run request, displays the stored style after completion, and renders persisted character cards after CHARACTERS completes. Project creation is unchanged.

## Tests

Backend tests use a deterministic `GeminiGateway` double: generated-style persistence, supplied-style-without-Gemini, character persistence, malformed/semantic-invalid output failure, later retry success, cap enforcement, and concurrent duplicate runs invoking the gateway once. Frontend tests cover style action input, generated style, cards, and existing state rendering.
