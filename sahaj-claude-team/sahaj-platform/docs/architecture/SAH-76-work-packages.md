# SAH-76 · Work packages for parallel development (2 java, 2 angular)

Story set: SAH-76 (76-1 to 76-6). Design: `docs/architecture/adr/ADR-027-ipd-nursing-integration-orders-to-execution.md`. Contract: `docs/api/ipd.yaml` 0.4.0. Database: one migration by the db-engineer (ADR-027 "Data needed"); no developer writes a migration.

Four packages. Each package has a backend half and a frontend half. Suggested staffing: **java A = WP1 + WP2**, **java B = WP3 + WP4**, **angular A = WP1 + WP2**, **angular B = WP3 + WP4** (pairs that share least code), or one package per person per side in any order once the day-0 contracts below exist. A file listed under a package is written only by that package. A file not listed is not touched by SAH-76.

## 0. Day-0 shared pieces (written first, by the named owner, merged before the others start)

Each is small: an interface or a record, with a stub implementation, so the other packages can compile and test against it.

| Piece | Path | Owner | Used by |
|---|---|---|---|
| Order domain model: `OrderKind`, `OrderPriority`, `OrderRef`, `MedicineOrderVersion` (record), `OrderHoldInterval`, `OrderTimeline` (versions + holds + stop of one order), `OrderLifecycle` (pure transitions), `OrderVersionDiff` | `backend/.../ipd/domain/order/*` (new package) | WP1 | WP2, WP3 |
| Timeline read: `OrderTimelineRepository` interface (`findTimelines(tenantId, admissionIds)`: versions, holds, stop of every order in one set read each) | `backend/.../ipd/application/OrderTimelineRepository.java` + `infrastructure/persistence/JdbcOrderTimelineRepository.java` | WP1 | WP3 (MAR, Medicine round, dose path), WP2 (notice details) |
| Pharmacist review creation port: `PharmacistReviews` (`openForDose(...)`, `closeForDose(...)`) | `backend/.../ipd/application/PharmacistReviews.java` | WP1 | WP3 |
| Notice port: `OrderNotices` (`record(OrderRef, kind, priority, actor)`, `acknowledgeByDose(orderId, actor)`, `openCounts(admissionIds)`) | `backend/.../ipd/application/OrderNotices.java` | WP2 | WP1 (order actions), WP3 (dose), WP2 card counts |
| Settings reader: `NursingSettingsReader` (`effectiveFor(tenantId, admissionId)` → `EffectiveNursingSettings` record: verificationRequired, windows, statWindow, unackMinutes, undoSeconds, vitalsInterval) | `backend/.../ipd/application/NursingSettingsReader.java`, `domain/EffectiveNursingSettings.java` | WP4 | WP1, WP2, WP3 |
| Audit device label: `AuditDevice` (reads `X-Sahaj-Device` and `User-Agent` for the current request; `Map<String,Object> label()`); helper `IpdAudit.with(after)` that adds `device` | `backend/.../ipd/web/AuditDeviceFilter.java`, `application/AuditDevice.java` | WP4 | all |
| Frontend contract models (TypeScript types mirroring the 0.4.0 schemas) | see each package's `*.model.ts`; shared new types in `frontend/src/app/features/ipd/orders/order.model.ts` (`OrderDetails`, `OrderChangeLine`, `OrderHold`, `OrderQuery`, `OrderPriority`, `NoticeCount`, `KeyLabel` re-export) | WP1 (angular) | all angular |
| Frontend device header: `ipd-device.ts` (make and keep the id in local storage) and `ipd-device.interceptor.ts` (adds the header to `/api/v1/ipd/` calls only) | `frontend/src/app/features/ipd/ipd-device.ts`, `ipd-device.interceptor.ts`; registration in `frontend/src/app/app.config.ts` | WP4 (angular) | all |
| `DatabaseBusy` handling in the client: no change (the platform problem-detail interceptor already shows the detail) | none | none | |

Rule for the shared pieces: the owner may add methods; changing a signature after day 0 needs a note to the other owners in the handoff.

## WP1 · Orders and pharmacist (stories 76-1, 76-2)

Endpoints: `POST /admissions/{id}/medicine-orders` (extended), `GET /admissions/{id}/emr` (extended rows), `POST /medicine-orders/{id}/change`, `/hold`, `/resume`, `/stop` (body), `/query`, `/query-reply`, `/verify` (orderVersion), `/reject` (QUERIED, review close), `GET /pharmacist-checks`, `GET /pharmacist-checks/summary` (queried, reviewsOpen), `GET /pharmacist-reviews`, `POST /pharmacist-reviews/{id}/mark-reviewed`.

Backend (java):
- `ipd/domain/order/*` (day-0 above), `ipd/domain/MedicineSafetyCheck.java` (live statuses widen to QUERIED and ON_HOLD), `ipd/domain/Frequency.java` (prn), new `ipd/domain/RouteKey.java`, `DoseUnit.java`.
- `ipd/application/MedicineOrderService.java`, `MedicineRules.java`, `MedicineViews.java`, `MedicineAssembler.java`, `MedicineOrderRepository.java`, `PharmacistCheckService.java`, `MedicineCatalogue.java`, `MedicineCatalogueStartupCheck.java`, `DrugCatalogueRepository.java`, `EmrIpdOrderAdapter.java` (port orders get version 1, ADR-027 D14); new `OrderChangeService.java` (change, hold, resume), `PharmacistQueryService.java`, `PharmacistReviewService.java`, `PharmacistReviews.java`, `OrderTimelineRepository.java`.
- `ipd/domain/Drug.java`, `DrugCatalogue.java` (routeKey, doseUnits, timeCritical).
- `ipd/infrastructure/persistence/JdbcMedicineOrderRepository.java`, `JdbcDrugCatalogueRepository.java`, new `JdbcOrderTimelineRepository.java`, `JdbcPharmacistReviewRepository.java`, `JdbcOrderQueryRepository.java`.
- `ipd/web/MedicineOrderController.java` and its request records.
- Unit and slice tests for the above under `backend/src/test/java/com/sahaj/ipd/...` (same names + `Test`).

Frontend (angular):
- `features/ipd/emr/*` (order form fields, Change, Hold, Resume, Stop with reason, query and reply on the row, execution line), `features/ipd/pharmacist/*` (row details, change lines, Query doctor sheet, Review after dose list and Mark reviewed, verify with orderVersion), `features/ipd/orders/order.model.ts` (shared types, day 0), new `features/ipd/emr/change-sheet.component.ts`, `hold-sheet.component.ts`, `stop-sheet.component.ts`, `features/ipd/pharmacist/query-sheet.component.ts`, `review-list.component.ts`.

## WP2 · Nurse order notices and acknowledgement (story 76-3)

Endpoints: `GET /admissions/{id}/order-notices`, `GET /order-notices`, `GET /order-notices/summary`, `POST /order-notices/{id}/acknowledge`; fields `AdmittedClinicalFlags.ordersToAcknowledge`, `NursingSheet.ordersToAcknowledge`, `Mar.ordersToAcknowledge` (value from `OrderNotices.openCounts`, placed in the Mar by WP3), `MarOrder.acknowledged` and `OrderExecution.acknowledged*` (values from WP2's port, placed by WP3 and WP1).

Backend (java):
- New `ipd/application/OrderNotices.java` (port, day 0), `OrderNoticeService.java`, `OrderNoticeViews.java`, `OrderNoticeRepository.java`, `ipd/domain/NoticeClock.java` (overdue and STAT line, pure), `ipd/infrastructure/persistence/JdbcOrderNoticeRepository.java`, `ipd/web/OrderNoticeController.java`.
- `ipd/application/AdmittedCards.java` (badge counts; also the one-line vitals call change supplied by WP4, see WP4).
- `ipd/application/ScopeGuard.java` support for the new guard kind `notice-locked`, and the contract-coverage test `IpdScopeCoverageTest` (it must also learn `dose-locked` for WP3; WP2 owns the test file and adds both kinds).
- `ipd/application/VitalsViews.java` is NOT WP2's: the `NursingSheet.ordersToAcknowledge` field is set in `VitalsService` by WP4 using `OrderNotices.openCounts` (see WP4).

Frontend (angular):
- `features/ipd/nursing/nursing-sheet.component.ts` and `nursing.model.ts` (the third tab "Orders" and its badge; WP4's vitals line is a child component WP2 places in the Vitals tab), new `features/ipd/nursing/orders-tab.component.ts`, `features/ipd/orders/order-notices.api.ts`, `order-notices.store.ts` (30 s polling through the existing `safety/visible-poll.ts`, unchanged), `stat-countdown.ts`.
- `features/ipd/safety/safety.component.ts` (place the panel), new `features/ipd/safety/orders-to-acknowledge.component.ts`.
- `features/ipd/admitted/patient-card.component.ts`, `admitted.model.ts` (badge).

## WP3 · MAR, give flow, override, PRN, STAT before check, undo and recorded in error (stories 76-4, 76-5 nurse side)

Endpoints: `GET /admissions/{id}/mar`, `POST /medicine-orders/{id}/doses`, `POST /doses/{id}/undo`, `POST /doses/{id}/recorded-in-error`; Medicine round of `GET /safety` (counts follow the new slot rule).

Backend (java):
- `ipd/domain/DoseSlots.java` (timeline input, new states, windows, ONCE and PRN; the one slot function), `MedicineRound.java`, `WristbandCheck.java`, new `MedicineCodeCheck.java`, `PrnRules.java`, `DoseCorrectionRules.java`, `ipd/domain/DoseOutcome.java`, `NotGivenReason.java` (VOMITED, OTHER), new `ScanOverrideReason.java`, `InErrorReason.java`.
- `ipd/application/MedicineAdministration.java`, `DoseService.java`, `DoseRules.java`, `DoseViews.java`, `DoseRepository.java`, `MarService.java`, `MarAssembler.java`, `MarViews.java`, `MedicineRoundReader.java`, `MedicineRoundRepository.java`, `SkippedOrderWarnings.java`, new `DoseCorrectionService.java`, `DrugCodeRepository.java`.
- `ipd/infrastructure/persistence/JdbcDoseRepository.java`, `JdbcMedicineRoundRepository.java`, new `JdbcDrugCodeRepository.java`.
- `ipd/web/MarController.java` (+ the new correction endpoints and request records).
- Calls `OrderNotices.acknowledgeByDose` (WP2) and `PharmacistReviews.openForDose/closeForDose` (WP1) inside the dose and correction transactions.
- `SafetyService.java`, `SafetyRows.java`, `SafetyViews.java` only where the Medicine round signature changes (no other change to the Safety board).

Frontend (angular):
- `features/ipd/mar/*` (`mar-tab.component.ts` counters, fold, PRN line, struck-through history, version marker; `give-sheet.component.ts` medicine code step, Checked by the system, override, STAT notice, PRN locks, new not-given reasons; `mar.model.ts`, `mar.api.ts`), new `features/ipd/mar/undo-toast.component.ts`, `dose-error-panel.component.ts`.
- `features/ipd/safety/medicine-round.component.ts` (only if the round row shape changes).

## WP4 · Settings, ward profiles, vitals due, audit device label (story 76-6)

Endpoints: `GET /nursing-settings`, `PUT /nursing-settings`, `POST /nursing-profiles`, `PUT /nursing-profiles/{key}`, `PUT /wards/{id}/nursing-profile`; fields `NursingSheet.vitalsDueAt`, `vitalsIntervalMinutes`, `ordersToAcknowledge` (value from WP2's port), and the vitals due rule used by `AdmittedClinicalFlags.vitalsDue` and `AdmissionDetail.vitalsDue`.

Backend (java):
- New `ipd/application/NursingSettingsReader.java` (day 0), `NursingSettingsService.java`, `NursingSettingsViews.java`, `NursingProfileRepository.java`, `ipd/domain/EffectiveNursingSettings.java`, `NursingSettingsRules.java` (ranges, key slug), `ipd/infrastructure/persistence/JdbcNursingProfileRepository.java`, `ipd/web/NursingSettingsController.java`.
- `ipd/application/IpdSettingsRepository.java`, `infrastructure/persistence/JdbcIpdSettingsRepository.java` (new `ipd.config` columns).
- `ipd/domain/VitalsDue.java` (interval argument; keeps a 240-minute overload until WP2 switches `AdmittedCards.java`, then removes it), `ipd/application/VitalsService.java`, `VitalsRules.java`, `VitalsViews.java`, `VitalsRepository.java`, `EmrVitalsAdapter.java`, `ipd/infrastructure/persistence/JdbcVitalsRepository.java` (stamp `due_interval_min`), `ipd/web/VitalsController.java`.
- Audit device: `ipd/web/AuditDeviceFilter.java`, `ipd/application/AuditDevice.java` (day 0).
- Change handed to WP2 (one line in `AdmittedCards.java`): call `VitalsDue.isDue(admitted, last, intervalMinutes, asOf)` with the interval read with the latest vitals.

Frontend (angular):
- New `features/config/ipd/nursing-settings.page.ts`, `nursing-settings.store.ts`, `nursing-settings.api.ts`; `features/config/config.routes.ts` and `features/config/config-sections.ts` (menu entry) — **built only after the human adds the page to the screen map (ADR-027 Q7)**; until then WP4 angular builds the vitals line and the device header only.
- New `features/ipd/nursing/vitals-due-line.component.ts` (placed by WP2 in the Vitals tab).
- `features/ipd/ipd-device.ts`, `ipd-device.interceptor.ts`, `app.config.ts` (registration only).

## 1. Files no package touches

`platform`, `patient`, `opd`, `billing` code; `backend/src/main/resources/db/migration/` (db-engineer); `frontend/e2e/` and `backend/src/test/java/**/it/` (qa-engineer); `docs/` outside the owners' folders.

## 2. Order of work and merge points

1. Day 0: WP1 order domain + timeline repository interface; WP2 `OrderNotices`; WP4 `NursingSettingsReader` and `AuditDevice`; WP1 angular `order.model.ts`. Stubs return defaults (verification On, built windows, no notices).
2. After the migration lands: each package replaces its stub with the real repository.
3. Merge point A (end of Sprint 1 scope): WP1 order, change, hold, stop, query; WP2 notices; WP4 settings API. MAR still on version 1 rules.
4. Merge point B (Sprint 2 scope): WP3 MAR, give flow, undo and error; WP1 review after dose; WP4 vitals due and audit device everywhere.
5. QA runs `node scripts/validate.mjs all` after merge point B only.

## 3. Conflicts to watch

- `DoseSlots` is the single slot rule (story 76-4 note): only WP3 edits it; WP2's STAT line and WP1's `OrderExecution.nextDueAt` call it, never re-implement it.
- `IpdScopeCoverageTest` and `ScopeGuard`: WP2 adds both new guard kinds (`notice-locked`, `dose-locked`) on day 0.
- The admission row lock is the only lock for every write; no package adds another.
