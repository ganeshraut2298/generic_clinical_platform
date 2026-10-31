---
paths:
  - "backend/**/*.java"
  - "backend/pom.xml"
  - "backend/src/main/resources/application*.yml"
---

# Java and Spring Boot rules

- **Java 17** (Spring Boot 3.x). No Java 21+ features: no virtual threads, no pattern matching for switch, no record patterns, no sequenced collections.
- Configuration from environment variables: `SAHAJ_DB_URL`, `SAHAJ_DB_APP_PASSWORD`, `SAHAJ_DB_MIGRATOR_PASSWORD`. The app connects as `sahaj_app`; Flyway runs as `sahaj_migrator`. Never connect as `postgres`.

- Package per module: `com.sahaj.<module>`, with `api` (public interfaces, events, DTOs other modules may use), `web` (controllers), `application` (services, transactions), `domain` (entities, rules), `infrastructure` (repositories, adapters). Only `api` is visible to other modules; Spring Modulith tests enforce it.
- Controllers are thin: validate (`@Valid`), check permission (`@PreAuthorize`), call one application service, map to the contract DTO. No entities in responses.
- Use Java records for DTOs. MapStruct or hand mapping; no reflection mappers.
- Transactions on application services only (`@Transactional`), never on controllers.
- Tenant: read it from the security context, set `app.tenant_id` on the connection per request; never accept tenant id from the client.
- Errors: throw domain exceptions; one `@RestControllerAdvice` maps them to RFC 9457 problem details. Never return stack traces.
- Persistence: Spring Data JPA; `ddl-auto=validate` (Flyway owns the schema). No native SQL string concatenation.
- Money is `BigDecimal` with scale 2 in INR; time is `Instant` in the database and IST only for display.
- Logging: SLF4J, structured, with `tenantId` and `requestId`. Never log names, mobile numbers, ABHA numbers or clinical text.
- Audit: clinical and billing changes publish an audit event (who, what, when, before/after ids).
- Tests: JUnit 5, AssertJ, Mockito; `@WebMvcTest` and `@DataJpaTest` slices; integration tests (`*IT.java`) run with profile `test` against the local `sahaj_test` database (URL from `SAHAJ_TEST_DB_URL`); Flyway `clean` is allowed only in that profile.
- Before handoff: `node scripts/validate.mjs backend`.
