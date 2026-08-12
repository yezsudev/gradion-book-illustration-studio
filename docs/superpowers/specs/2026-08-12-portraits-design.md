# PORTRAITS milestone design

PORTRAITS remains one ordered pipeline claim executed sequentially on the existing request thread. It is legal only after CHARACTERS is completed. Each character is processed independently: the gateway returns one image interaction, the image is atomically moved into `data/projects/<project-id>/portraits/<character-id>.png`, and the character row is updated before the next character starts. A later failure leaves earlier completed portraits intact and fails only the PORTRAITS step.

Character rows store `portrait_status`, `portrait_path`, `portrait_error`, `portrait_generated_at`, and `portrait_interaction_id`. Retry reuses the normal state-machine claim and skips completed rows. Context recovery reuses the existing persisted Gemini references when available; otherwise it rebuilds only the book/style/characters context needed for the next portrait.

Project detail returns portrait status and a nullable authorized `portraitUrl`, never a filesystem path. `GET /api/projects/{projectId}/media/{characterId}` checks project ownership before streaming stored PNG bytes. Tests use a fake image gateway and a small PNG fixture; no real image calls run in tests.
