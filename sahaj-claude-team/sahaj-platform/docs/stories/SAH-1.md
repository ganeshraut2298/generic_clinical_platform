# SAH-1 · Project scaffold
Mockup screen: Foundation (no screen)

### SAH-1-1 · As a java-developer, I want a single Spring Boot 3.x / Java 17 Maven application with the six module packages, so that every later backend story has a place to put its code without inventing structure
Priority: Must
## Acceptance criteria
- Given a clean checkout, when a developer runs the backend build command, then `backend/pom.xml` exists, declares Java 17 and Spring Boot 3.x, and the build compiles with no module-specific feature code.
- Given the compiled backend, when the package structure is inspected, then exactly six top-level module packages exist: `com.sahaj.platform`, `com.sahaj.patient`, `com.sahaj.opd`, `com.sahaj.ipd`, `com.sahaj.hra`, `com.sahaj.billing`.
- Given one of the six module packages, when it is inspected, then it contains at minimum an `api` sub-package, matching `CLAUDE.md`'s rule that modules talk only through `api` or domain events.
- Given the application, when it starts in the `dev` profile with no database and no tenancy code present yet, then the Spring context loads successfully (this story adds no controllers, repositories or schema).
- Given the empty `backend/src` tree that exists today, when this story is delivered, then it is replaced by the scaffold above; no `.gitkeep` placeholder remains needed for `backend/src`.
## Notes for design
- Package/module internals, naming beyond the six top-level packages, and any API/table design are for the solution-architect, db-engineer and java-developer to decide — not specified here.

### SAH-1-2 · As a solution-architect/java-developer, I want an automated check that fails the build when one module reaches into another module's internals, so that the modular-monolith boundary from `CLAUDE.md` is enforced by the build, not by code review alone
Priority: Must
## Acceptance criteria
- Given Spring Modulith is added to the backend, when the module-verification test runs, then it fails the build if any module package references a class in another module's package outside that module's `api` package.
- Given a module accesses another module only through its `api` package or a domain event, when the module-verification test runs, then the build passes.
- Given this story introduces no second module with real business logic yet, when the verification test runs against the six empty/skeletal module packages, then it still runs and passes (a smoke proof that the check is wired in, ready to catch violations once SAH-2 onward add real code).
## Notes for design
- Whether the check also enforces one-schema-per-module (relevant from SAH-2) is listed as an open question in `docs/requirements/SAH-1.md`; do not assume an answer.

### SAH-1-3 · As an angular-developer, I want a single Angular application with Angular Material and working lint/test/build scripts, so that every later frontend story has a place to put its code without inventing structure
Priority: Must
## Acceptance criteria
- Given a clean checkout, when a developer runs `npm --prefix frontend install`, then `frontend/package.json` exists, declares an Angular current-major version and Angular Material as a dependency, and install completes with no errors.
- Given the installed frontend, when `npm --prefix frontend run lint` is run, then it completes with exit code 0 against the scaffold's own generated files.
- Given the installed frontend, when `npm --prefix frontend run test` is run, then it completes with exit code 0 (the default generated app test, since no feature screens exist yet).
- Given the installed frontend, when `npm --prefix frontend run build` is run, then it produces a build with exit code 0.
- Given the empty `frontend/` tree that exists today (only `.gitkeep`), when this story is delivered, then it is replaced by the scaffold above; no `.gitkeep` placeholder remains needed.
## Notes for design
- No screen, route, component layout or theme is specified by this story — that is SAH-4 (App shell and theme), which must match the mockup. This story only proves the Angular + Material toolchain works end to end.

### SAH-1-4 · As any developer agent, I want configuration read only from environment variables with every variable listed in `.env.example`, so that no secret is ever committed and every later story follows the same pattern
Priority: Must
## Acceptance criteria
- Given the scaffold's backend and frontend configuration, when the repository is searched for connection strings, passwords, API keys or tokens, then none are found in committed source, build files or config files.
- Given the scaffold needs configuration values (for example, server port, active Spring profile), when they are required, then they are read from environment variables, not hardcoded.
- Given `.env.example` (currently deleted per git status), when this story is delivered, then `.env.example` is re-created at the repository root, listing every environment variable name the scaffold reads, with no real values — placeholders only.
- Given a developer copies `.env.example` to `.env` and fills in local values, when they run the backend and frontend start commands, then both start successfully using those values (to the extent the scaffold has any runtime configuration at all at this stage).
## Notes for design
- `.env` itself remains human-only per `CLAUDE.md`'s ownership table; `.env.example` is the artifact this story's developer agents produce and maintain going forward. This is flagged as an open question for the human to confirm in `docs/requirements/SAH-1.md`.

### SAH-1-5 · As a qa-engineer, I want `node scripts/validate.mjs all` to pass on a clean checkout, so that the foundation story is provably done before any other story starts
Priority: Must
## Acceptance criteria
- Given the completed scaffold (backend, frontend, `.env.example`), when `node scripts/validate.mjs all` is run on a clean checkout, then it passes with no errors, covering `frontend`, `backend`, `db` and `docs` checks.
- Given `validate backend` specifically runs backend build/tests, when it runs against the SAH-1 scaffold (no schema, no tenancy code yet), then it passes without requiring any story-specific feature code.
- Given `validate db` runs against the empty `backend/src/main/resources/db/migration/` folder, when it runs, then it passes (no migrations are expected from this story; the folder structure is preserved for SAH-2 onward).
- Given the open question on live-database dependency for `mvn verify` is unresolved, when qa-engineer or the developers hit a failure caused only by `sahaj_test` not existing locally, then they report BLOCKED with that specific cause rather than guessing a fix, and escalate per `docs/requirements/SAH-1.md`'s open questions.
## Notes for design
- This story does not specify qa-engineer's own test-plan format or e2e scaffolding (there is no UI to test yet); it only requires that the validation gate passes.
