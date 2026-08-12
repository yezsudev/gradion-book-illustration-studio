# Decisions

## Spring Boot and H2 over Node.js

The initial direction considered a Node.js backend, mainly because it would keep
the frontend and backend in the JavaScript/TypeScript ecosystem.

During architecture planning, AI proposed Spring Boot with H2 instead. I
initially questioned the change because Node.js was the original direction, but
decided to keep Spring Boot because it is the backend stack I am more familiar
with.

Given the limited scope and time of the assessment, using a familiar stack lets
me spend more time on the parts that matter more here: pipeline correctness,
persistence, concurrency, recovery, and testing.

H2 in file mode keeps local setup simple while still providing persistence and
transaction support. The trade-off is that H2 is less representative of a
typical production database such as PostgreSQL, but adding an external database
service would add complexity without helping the core requirements of this
assessment.

## Server-instance ownership for stranded pipeline recovery

AI initially proposed treating any pipeline step that remained `RUNNING` longer
than a configurable timeout as stale and therefore recoverable.

I pushed back because a slow but still-active Gemini request could exceed that
timeout. Allowing the user to recover and retry at that point could start a
second billable Gemini call while the first one was still running, violating the
no-duplicate-execution requirement.

We kept the configurable stale threshold, but also associate a running
execution with the backend instance that claimed it. A stale execution from a
previous server instance can be recovered, while a step still owned by the
current process is not considered abandoned solely because it is slow.

The cost is additional execution metadata and recovery logic, but it gives
stronger protection against duplicate external API calls without introducing a
queue, worker, heartbeat, or distributed lock.

## Keep optional style in the STYLE step

During implementation, AI suggested collecting the optional user-provided style
when creating a project.

I rejected that approach because project creation only needs the project title
and book content, while style is part of the first pipeline step. Keeping the
style input on the project detail screen makes the UI follow the same sequence
as the pipeline and keeps project creation focused on persistence rather than
generation options.

If the user provides a style, the application persists and uses it directly
instead of making a Gemini request only to rewrite or normalize it. If no style
is supplied, Gemini generates one from the book.

The trade-off is that STYLE has two execution paths, but it avoids an unnecessary
external API call and keeps the user interaction aligned with the pipeline.

## Gemini for text and Hugging Face FLUX for images

The initial implementation used Gemini for both text and image generation so
that the application could follow a single AI provider and stay close to the
reference notebook.

Manual testing showed that the available Gemini Free Tier had zero quota for the
required image-generation model. The Gemini image integration therefore could
not be exercised end-to-end without enabling billing.

I kept Gemini for the text stages — STYLE, CHARACTERS, and CHAPTERS — and moved
image generation for PORTRAITS and ILLUSTRATIONS behind a small image-generation
boundary implemented with Hugging Face and FLUX.1-schnell.

AI initially suggested a hard-coded Hugging Face `fal-ai` provider route. Real
integration testing returned `Model not supported by provider fal-ai`, so I did
not keep that assumption. The provider was made configurable and the working
provider was verified through a real image-generation request.

This adds a second external AI provider and some configuration overhead, but it
keeps the text pipeline close to the Gemini notebook while making the full
five-step flow runnable without requiring paid Gemini image quota.

## Sequential portrait generation instead of background workers

For the PORTRAITS step, two designs were considered: processing portraits
sequentially inside the existing claimed pipeline execution, or moving image
generation to background jobs so progress could continue independently of the
HTTP request.

I chose one PORTRAITS claim with sequential per-character generation. Each
successful portrait is written atomically and persisted immediately before
processing the next character.

This means that if the first portrait succeeds and the second fails, the first
result remains available and retry only needs to process the unfinished
character.

The trade-off is that the request can remain open while external image
generation is running. However, the assessment caps the number of characters at
two, so introducing queues, workers, scheduling, and additional lifecycle
management would add more complexity than value for this scope.