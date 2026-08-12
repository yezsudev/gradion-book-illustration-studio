# Decisions

# Engineering Decisions

## Spring Boot and H2 for the backend

The initial direction considered a Node.js backend, mainly because it would keep
the frontend and backend in the JavaScript/TypeScript ecosystem.

During architecture planning, Spring Boot with H2 was proposed instead. I
initially questioned the change because Node.js was the original direction, but
decided to use Spring Boot because it is the backend stack I am more familiar
with.

Given the limited scope and time of the assessment, using a familiar stack lets
me spend more time on the parts that matter more here: pipeline correctness,
persistence, concurrency, recovery, and testing.

H2 in file mode keeps local setup simple while still providing persistence and
transaction support. The trade-off is that H2 is less representative of a
typical production database such as PostgreSQL, but adding an external database
service would add complexity without helping the core requirements of this
assessment.

This file records decisions as they happen. At this planning-only phase, no implementation decision, AI disagreement, test report, or trade-off evidence is being invented. Proposed options remain in `docs/architecture.md` and `docs/plan.md`; each real decision will be added with its proposer, pushback, outcome, and accepted cost.
