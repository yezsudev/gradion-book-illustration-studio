# PORTRAITS implementation plan

1. Add failing backend tests for ordering, per-character persistence, partial failure/retry, duplicate claims, detail projection, media ownership, and fake image calls.
2. Add failing frontend tests for pending/running, completed image URL, and failed portrait states.
3. Extend character schema/DTOs and `ProjectFiles` with safe atomic portrait writes and reads.
4. Extend `GeminiGateway`/HTTP implementation with one portrait image interaction and minimal context recovery hooks.
5. Process PORTRAITS sequentially inside `PipelineService`, updating each character immediately and preserving completed items on failure.
6. Add ownership-checked media streaming and project-detail fields.
7. Render portrait progress/images/errors in the existing detail page.
8. Run backend tests, frontend tests, root `npm test`, and root `npm run build`.
