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