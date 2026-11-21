package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-78 hardening round on opening stock (contract docs/api/pharmacy.yaml 0.2.1; DEC-076, DEC-077): an owner may reject a DRAFT,
 * the cap counts changed rows written (at most 2 x rowCount), at most 10 starts per person per store per IST day, a store
 * switched off meanwhile answers 404 on start, submit and approve, and the product row lock. Written before the code: these
 * tests fail until the java-developer and the db-engineer land the round. Test plan: docs/test-plans/SAH-78.md section 25
 * (T78-H-*).
 */
@OnTestDatabase
class PharmacyHardeningImportIT extends Sah79Support {

    private static final String STATE_TEXT = "This import can no longer be changed.";
    private static final String REJECT = "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}";
    private static final String LIMIT_TEXT = "Too many files today. Try again tomorrow.";

    private record World(Clinic clinic, JsonNode central, JsonNode pharmacy, Pn keeper, Pn pharmacyKeeper, Pn second, String codeA, String codeB, String ownId) {

        String centralId() {
            return central.get("id").asText();
        }

        String pharmacyId() {
            return pharmacy.get("id").asText();
        }
    }

    private World world() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn keeper = crew(clinic, "PHARMACIST", "Pia Pharmacist");
        mapStaff(clinic, idOf(stores[0]), keeper, "CENTRAL_STORE_USER");
        Pn pharmacyKeeper = crew(clinic, "PHARMACIST", "Pat Pharmacist");
        mapStaff(clinic, idOf(stores[1]), pharmacyKeeper, "STORE_USER");
        Pn second = crew(clinic, "OWNER", "Second Owner");
        adopt(clinic, "G-000101");
        JsonNode own = createOwn(clinic, ownBody("Test dressing kit (OT)"));
        return new World(clinic, stores[0], stores[1], keeper, pharmacyKeeper, second, "G-000101", own.get("code").asText(), own.get("productId").asText());
    }

    private Map<String, Object> cleanRow(World w, int rowNo) {
        return row(rowNo, "CS", w.codeA(), "B" + rowNo, farExpiry(), "10", "5", "1");
    }

    private static Map<String, Object> body(List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        return m;
    }

    private List<Map<String, Object>> cleanRows(World w, int from, int to) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            list.add(cleanRow(w, i));
        }
        return list;
    }

    private String linesUrl(World w, Object importId) {
        return importUrl(w.centralId(), importId) + "/lines";
    }

    private String rowsWritten(World w, String importId) throws Exception {
        return values(w.clinic(), "select line_rows_written::text from pharmacy.opening_stock_import where id = ?::uuid", importId).get(0);
    }

    private String lineSnapshot(World w, String importId) throws Exception {
        return values(w.clinic(), "select coalesce(string_agg(row_no || ':' || version || ':' || coalesce(updated_at::text, '-') || ':' || batch_raw, ',' "
                + "order by row_no), '') from pharmacy.opening_stock_line where import_id = ?::uuid", importId).get(0);
    }

    private String statusOf(World w, String importId) throws Exception {
        return values(w.clinic(), "select status from pharmacy.opening_stock_import where id = ?::uuid", importId).get(0);
    }

    // ---------------- DEC-076: an owner may reject a DRAFT ----------------

    @Test
    void anOwnerRejectsADraftWithAReasonAndTheDraftHasNoSubmittedAtAndTheStoreAndProductsAreFreeAtOnce() throws Exception {
        World w = world();
        String id = idOf(draftOfCleanFile(w.pharmacyKeeper().auth(), "PHARM", w.pharmacyId(), w.codeA(), w.codeB()));
        assertThat(statusOf(w, id)).isEqualTo("DRAFT");
        // the draft holds the store
        assertType(problem(call(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.clinic().owner(), null), 409), "open-import");
        JsonNode rejected = ok(HttpMethod.POST, importUrl(w.pharmacyId(), id) + "/reject", w.second().auth(), "{\"reasonKey\":\"WRONG_STORE\",\"note\":null}", 200);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejectReasonKey").asText()).isEqualTo("WRONG_STORE");
        assertThat(rejected.get("submittedAt").isNull()).as("a rejected draft has no submittedAt").isTrue();
        assertThat(rejected.get("decidedBy").get("membershipId").asText()).isEqualTo(w.second().membershipId().toString());
        assertThat(rejected.get("decidedAt").isNull()).isFalse();
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_REJECTED")).hasSize(1);
        // freed at once: the store may be switched off, and the uploader may start again at another store
        assertThat(ok(HttpMethod.POST, STORES + "/" + w.pharmacyId() + "/switch-off", w.clinic().owner(), null, 200).get("status").asText()).isEqualTo("INACTIVE");
    }

    @Test
    void theUploaderMayStillReplaceTheirDraftAndAnOwnerWhoUploadedItMayRejectItAndOtherReasonsAndNotesFollowTheSameRules() throws Exception {
        World w = world();
        String mine = idOf(draftOfCleanFile(w.clinic().owner(), "CS", w.centralId(), w.codeA(), w.codeB()));
        // the uploader-owner rejects his own draft (rejecting approves nothing)
        assertThat(ok(HttpMethod.POST, importUrl(w.centralId(), mine) + "/reject", w.clinic().owner(), REJECT, 200).get("status").asText()).isEqualTo("REJECTED");
        // OTHER needs a note of 4 to 300 characters, also for a draft
        String draft = idOf(startImport(w.keeper().auth(), w.centralId(), 2));
        String url = importUrl(w.centralId(), draft) + "/reject";
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.second().auth(), "{\"reasonKey\":\"OTHER\",\"note\":\"abc\"}"), 400), "note", "Write at least 4 characters");
        assertFieldMessage(problem(call(HttpMethod.POST, url, w.second().auth(), "{\"reasonKey\":null,\"note\":null}"), 400), "reasonKey", "Choose a reason");
        assertThat(statusOf(w, draft)).as("the refusals changed nothing").isEqualTo("DRAFT");
        assertThat(ok(HttpMethod.POST, url, w.second().auth(), "{\"reasonKey\":\"OTHER\",\"note\":\"abcd\"}", 200).get("rejectNote").asText()).isEqualTo("abcd");
        // starting again replaces the uploader's own draft as before (no owner needed)
        String first = idOf(startImport(w.keeper().auth(), w.centralId(), 1));
        String again = idOf(startImport(w.keeper().auth(), w.centralId(), 1));
        assertThat(statusOf(w, first)).isEqualTo("REPLACED");
        assertThat(statusOf(w, again)).isEqualTo("DRAFT");
    }

    @Test
    void aPharmacistAndAnyoneWhoIsNotAnOwnerGets403RejectingADraftAndNothingChanges() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 2));
        Pn nurse = crew(w.clinic(), "NURSE");
        for (RequestPostProcessor who : List.of(w.keeper().auth(), nurse.auth(), crew(w.clinic(), "DOCTOR").auth())) {
            assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/reject", who, REJECT), 403), "forbidden");
        }
        assertThat(statusOf(w, id)).isEqualTo("DRAFT");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_REJECTED")).isEmpty();
    }

    @Test
    void anyOtherStateThanDraftOrWaitingAnswers409ImportStateOnReject() throws Exception {
        World w = world();
        // NOT_ACCEPTED: a file with an error is submitted and refused
        String notAccepted = idOf(startImport(w.keeper().auth(), w.centralId(), 1));
        addRows(w.keeper().auth(), w.centralId(), notAccepted, rows(row(1, "CS", "G-999999", "NA1", farExpiry(), "10", "5", "1")));
        assertType(problem(call(HttpMethod.POST, importUrl(w.centralId(), notAccepted) + "/submit", w.keeper().auth(), null), 422), "opening-stock-invalid");
        assertThat(statusOf(w, notAccepted)).isEqualTo("NOT_ACCEPTED");
        // REPLACED
        String replaced = idOf(startImport(w.keeper().auth(), w.centralId(), 1));
        String current = idOf(startImport(w.keeper().auth(), w.centralId(), 1));
        assertThat(statusOf(w, replaced)).isEqualTo("REPLACED");
        // REJECTED (a rejected draft cannot be rejected again)
        ok(HttpMethod.POST, importUrl(w.centralId(), current) + "/reject", w.second().auth(), REJECT, 200);
        // APPROVED
        String approved = idOf(draftOfCleanFile(w.keeper().auth(), "CS", w.centralId(), w.codeA(), w.codeB()));
        submit(w.keeper().auth(), w.centralId(), approved);
        ok(HttpMethod.POST, importUrl(w.centralId(), approved) + "/approve", w.second().auth(), null, 200);
        for (String id : List.of(notAccepted, replaced, current, approved)) {
            JsonNode p = problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/reject", w.second().auth(), REJECT), 409);
            assertType(p, "import-state");
            assertThat(p.get("detail").asText()).isEqualTo(STATE_TEXT);
        }
        assertThat(statusOf(w, approved)).isEqualTo("APPROVED");
        assertThat(statusOf(w, notAccepted)).isEqualTo("NOT_ACCEPTED");
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_REJECTED")).as("only the one rejection of a draft").hasSize(1);
    }

    @Test
    void theDatabaseAllowsDraftToRejectedWithoutSubmittedAtAndStillRefusesDraftToApprovedAndEveryChangeOfAFinalState() throws Exception {
        World w = world();
        String draft = idOf(startImport(w.keeper().auth(), w.centralId(), 1));
        String decider = w.second().membershipId().toString();
        db().inTenant(w.clinic().tenantId(), c -> {
            mustRefuse(c, "23514", "cannot go from draft to APPROVED", "update pharmacy.opening_stock_import set status = 'APPROVED', decided_by = ?::uuid, decided_at = now() where id = ?::uuid",
                    decider, draft);
            thatRefusal(refused(c, "update pharmacy.opening_stock_import set status = 'REJECTED', reject_reason_key = 'WRONG_FILE', decided_by = ?::uuid, decided_at = now() "
                    + "where id = ?::uuid", decider, draft)).as("DRAFT to REJECTED with no submitted_at is allowed").isNull();
            assertThat(str(c, "select status from pharmacy.opening_stock_import where id = ?::uuid", draft)).isEqualTo("REJECTED");
            mustRefuse(c, "23514", "is final (REJECTED)", "update pharmacy.opening_stock_import set status = 'DRAFT' where id = ?::uuid", draft);
            return null;
        });
        // a rejected draft keeps the decision check: a reason and a decider are needed
        String second = idOf(startImport(w.keeper().auth(), w.centralId(), 1));
        db().inTenant(w.clinic().tenantId(), c -> {
            SQLException noReason = refused(c, "update pharmacy.opening_stock_import set status = 'REJECTED', decided_by = ?::uuid, decided_at = now() where id = ?::uuid", decider, second);
            thatRefusal(noReason).as("no reason, no rejection").isNotNull();
            assertThat(noReason.getSQLState()).isEqualTo("23514");
            return null;
        });
    }

    // ---------------- DEC-077 item 2: the cap counts changed rows written (2 x rowCount) ----------------

    @Test
    void theCapIsTwiceTheRowCountOfChangedRowsAndTheNextChangedRowIs409ImportStateAndWritesNothing() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 3)); // 2 x 3 = 6 changed rows
        ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(cleanRows(w, 1, 3))), 200);
        assertThat(rowsWritten(w, id)).isEqualTo("3");
        List<Map<String, Object>> changed = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            changed.add(row(i, "CS", w.codeA(), "CHG" + i, farExpiry(), "10", "5", "1"));
        }
        ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(changed)), 200);
        assertThat(rowsWritten(w, id)).isEqualTo("6");
        String lines = lineSnapshot(w, id);
        List<Map<String, Object>> more = List.of(row(1, "CS", w.codeA(), "CHG1X", farExpiry(), "10", "5", "1"));
        JsonNode refused = problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(more))), 409);
        assertType(refused, "import-state");
        assertThat(refused.get("detail").asText()).isEqualTo(STATE_TEXT);
        assertThat(rowsWritten(w, id)).as("a refused request does not count").isEqualTo("6");
        assertThat(lineSnapshot(w, id)).as("nothing written").isEqualTo(lines);
        assertThat(getImport(w.keeper().auth(), w.centralId(), id).get("status").asText()).as("still a draft").isEqualTo("DRAFT");
        assertThat(submit(w.keeper().auth(), w.centralId(), id).get("status").asText()).as("every row arrived: submit still works").isEqualTo("WAITING_APPROVAL");
        assertThat(rowsWritten(w, id)).as("frozen at 6").isEqualTo("6");
    }

    @Test
    void anUnchangedResendNeverCountsSoManyRetriesDoNotRunIntoTheCap() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 2)); // cap 4
        ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(cleanRows(w, 1, 2))), 200);
        assertThat(rowsWritten(w, id)).isEqualTo("2");
        String lines = lineSnapshot(w, id);
        for (int i = 0; i < 12; i++) {
            ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(cleanRows(w, 1, 2))), 200);
        }
        assertThat(rowsWritten(w, id)).as("twelve unchanged resends count nothing").isEqualTo("2");
        assertThat(lineSnapshot(w, id)).isEqualTo(lines);
        ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of(row(2, "CS", w.codeA(), "FIXED2", farExpiry(), "10", "5", "1")))), 200);
        assertThat(rowsWritten(w, id)).as("only the changed row counts").isEqualTo("3");
    }

    @Test
    void oneRequestThatWouldPassTheCapIsRefusedWholeAndAfterTheCapIsUsedUpAnyRequestIncludingABadBodyAnswers409() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 4)); // cap 8
        ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(cleanRows(w, 1, 4))), 200);
        List<Map<String, Object>> three = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            three.add(row(i, "CS", w.codeA(), "ONE" + i, farExpiry(), "10", "5", "1"));
        }
        ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(three)), 200);
        assertThat(rowsWritten(w, id)).isEqualTo("7");
        String lines = lineSnapshot(w, id);
        List<Map<String, Object>> again = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            again.add(row(i, "CS", w.codeA(), "TWO" + i, farExpiry(), "10", "5", "1"));
        }
        assertType(problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(again))), 409), "import-state");
        assertThat(rowsWritten(w, id)).as("the whole request is refused, not part of it").isEqualTo("7");
        assertThat(lineSnapshot(w, id)).isEqualTo(lines);
        ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of(again.get(0)))), 200);
        assertThat(rowsWritten(w, id)).as("one changed row still fits").isEqualTo("8");
        // used up: a good request, an empty one, an unreadable one and a row beyond the row count: 409 import-state each
        assertType(problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of(again.get(1))))), 409), "import-state");
        assertType(problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of()))), 409), "import-state");
        assertType(problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of(cleanRow(w, 5))))), 409), "import-state");
        assertThat(rowsWritten(w, id)).isEqualTo("8");
    }

    @Test
    void afterTheCapABodyThatReadsAsJsonButBreaksAFieldRuleAnswers409AndABodyThatCannotBeReadAnswers400() throws Exception {
        // after the cap: a body that reads as JSON but breaks a field rule answers 409 import-state;
        // a body that cannot be read answers 400 malformed-request; the rows written stay at 2
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 1)); // cap 2
        for (int i = 0; i < 2; i++) {
            ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of(row(1, "CS", w.codeA(), "U" + i, farExpiry(), "10", "5", "1")))), 200);
        }
        // reads as JSON, breaks a field rule: 409 import-state
        assertType(problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of(cleanRow(w, 5))))), 409), "import-state"); // rowNo outside 1..rowCount
        assertType(problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), "{\"rows\":[]}"), 409), "import-state");
        assertType(problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(),
                js(body(List.of(row(1, "CS", w.codeA(), "B".repeat(200), farExpiry(), "10", "5", "1"))))), 409), "import-state"); // a batch of 200 characters
        // cannot be read: 400 malformed-request
        for (String bad : new String[] {"{\"rows\":", "not json", "[]", "{\"rows\":\"text\"}", "{\"rows\":[{\"rowNo\":\"one\"}]}"}) {
            assertThat(problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), bad), 400).get("type").asText()).as("body: " + bad).matches(".*(malformed-request|validation)$");
        }
        // an unknown field: the contract allows malformed-request or validation (one field error)
        String unknown = problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), "{\"rows\":[],\"surprise\":1}"), 400).get("type").asText();
        assertThat(unknown).as("an unknown field").matches(".*(malformed-request|validation)$");
        assertThat(rowsWritten(w, id)).isEqualTo("2");
    }

    @Test
    void theCapFollowsTwiceTheRowCountAtTheBoundaries() throws Exception {
        World w = world();
        for (int rowCount : new int[] {1, 2, 5}) {
            String id = idOf(startImport(w.keeper().auth(), w.centralId(), rowCount));
            for (int i = 1; i <= 2 * rowCount; i++) {
                ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of(row(1, "CS", w.codeA(), "B" + i, farExpiry(), "10", "5", "1")))), 200);
            }
            assertType(problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(),
                    js(body(List.of(row(1, "CS", w.codeA(), "ONEMORE", farExpiry(), "10", "5", "1"))))), 409), "import-state");
            assertThat(rowsWritten(w, id)).as("rowCount " + rowCount).isEqualTo(String.valueOf(2 * rowCount));
        }
    }

    @Test
    void aBadRequestAnotherPharmacistOrAnUnknownImportCountsNothing() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 2));
        Pn keeper2 = crew(w.clinic(), "PHARMACIST", "Second Pharmacist");
        mapStaff(w.clinic(), w.centralId(), keeper2, "CENTRAL_STORE_USER");
        problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of()))), 400);
        problem(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of(cleanRow(w, 3))))), 400);
        assertType(problem(call(HttpMethod.POST, linesUrl(w, id), keeper2.auth(), js(body(cleanRows(w, 1, 2)))), 403), "store-permission-denied");
        assertType(problem(call(HttpMethod.POST, linesUrl(w, UUID.randomUUID()), w.keeper().auth(), js(body(cleanRows(w, 1, 2)))), 404), "not-found");
        assertThat(rowsWritten(w, id)).isEqualTo("0");
    }

    @Test
    void parallelRequestsCannotPassTheRowCapTogether() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 1)); // cap 2 changed rows
        List<RequestPostProcessor> callers = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            callers.add(w.keeper().auth());
            bodies.add(js(body(List.of(row(1, "CS", w.codeA(), "PAR" + i, farExpiry(), "10", "5", "1")))));
        }
        List<String> answers = atTheSameMoment(HttpMethod.POST, linesUrl(w, id), callers, bodies);
        assertThat(statusCount(answers, 200)).as(answers.toString()).isEqualTo(2);
        assertThat(statusCount(answers, 409)).isEqualTo(6);
        assertThat(rowsWritten(w, id)).isEqualTo("2");
        assertThat(pharmacyRows(w.clinic(), "opening_stock_line")).isOne();
    }

    @Test
    void theRowCounterOnlyEverGoesUpInADraftIsFrozenOnceTheImportWaitsAndTheOldRequestCounterIsNoLongerWritten() throws Exception {
        World w = world();
        String draft = idOf(startImport(w.keeper().auth(), w.centralId(), 1));
        ok(HttpMethod.POST, linesUrl(w, draft), w.keeper().auth(), js(body(cleanRows(w, 1, 1))), 200);
        assertThat(rowsWritten(w, draft)).isEqualTo("1");
        assertThat(values(w.clinic(), "select line_request_count::text from pharmacy.opening_stock_import where id = ?::uuid", draft).get(0))
                .as("the 0.1.3 counter stays in place and is not written").isEqualTo("0");
        commitAsTenant(w.clinic().tenantId(), "update pharmacy.opening_stock_import set line_rows_written = 2 where id = ?::uuid", draft);
        assertThat(rowsWritten(w, draft)).isEqualTo("2");
        for (String value : new String[] {"-1", "1", "0"}) {
            assertThatThrownBy(() -> db().inTenant(w.clinic().tenantId(), c -> {
                DbAccess.execute(c, "update pharmacy.opening_stock_import set line_rows_written = " + value + " where id = ?::uuid", draft);
                return null;
            })).as("line_rows_written = " + value).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
        }
        assertThat(rowsWritten(w, draft)).as("never down").isEqualTo("2");
        submit(w.keeper().auth(), w.centralId(), draft);
        for (String set : List.of("status = 'APPROVED', decided_by = ?::uuid, decided_at = now(), line_rows_written = line_rows_written + 1",
                "status = 'REJECTED', reject_reason_key = 'WRONG_FILE', decided_by = ?::uuid, decided_at = now(), line_rows_written = 0")) {
            assertThatThrownBy(() -> db().inTenant(w.clinic().tenantId(), c -> {
                DbAccess.execute(c, "update pharmacy.opening_stock_import set " + set + " where id = ?::uuid", w.second().membershipId().toString(), draft);
                return null;
            })).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
        }
        assertThat(ok(HttpMethod.POST, importUrl(w.centralId(), draft) + "/approve", w.second().auth(), null, 200).get("status").asText()).isEqualTo("APPROVED");
        assertThat(rowsWritten(w, draft)).isEqualTo("2");
    }

    @Test
    void theCounterStartsAtZeroForEveryNewImportAndTheBudgetBelongsToTheClinic() throws Exception {
        World a = world();
        World b = world();
        String first = idOf(startImport(a.keeper().auth(), a.centralId(), 1));
        assertThat(rowsWritten(a, first)).isEqualTo("0");
        for (int i = 0; i < 2; i++) {
            ok(HttpMethod.POST, linesUrl(a, first), a.keeper().auth(), js(body(List.of(row(1, "CS", a.codeA(), "S" + i, farExpiry(), "10", "5", "1")))), 200);
        }
        assertType(problem(call(HttpMethod.POST, linesUrl(a, first), a.keeper().auth(), js(body(List.of(row(1, "CS", a.codeA(), "S9", farExpiry(), "10", "5", "1"))))), 409), "import-state");
        // starting again gives a new budget and the used-up draft becomes REPLACED
        String second = idOf(startImport(a.keeper().auth(), a.centralId(), 1));
        assertThat(rowsWritten(a, second)).isEqualTo("0");
        assertThat(statusOf(a, first)).isEqualTo("REPLACED");
        // the other clinic has its own counter
        String idB = idOf(startImport(b.keeper().auth(), b.centralId(), 1));
        assertThat(rowsWritten(b, idB)).isEqualTo("0");
        ok(HttpMethod.POST, linesUrl(b, idB), b.keeper().auth(), js(body(cleanRows(b, 1, 1))), 200);
        assertType(problem(call(HttpMethod.POST, linesUrl(a, second), b.keeper().auth(), js(body(cleanRows(a, 1, 1)))), 404), "not-found");
    }

    // ---------------- DEC-077 item 3: at most 10 starts per person per store per IST day ----------------

    @Test
    void theEleventhStartOfOnePersonAtOneStoreInADayIs409ImportLimitAndWritesNothing() throws Exception {
        World w = world();
        List<String> keys = new ArrayList<>();
        String last = null;
        for (int i = 1; i <= 10; i++) {
            UUID key = UUID.randomUUID();
            keys.add(key.toString());
            last = idOf(ok(HttpMethod.POST, importsUrl(w.centralId()), w.keeper().auth(), js(startBody(key, 1)), 201));
        }
        assertThat(values(w.clinic(), "select count(*)::text from pharmacy.opening_stock_import where store_id = ?::uuid and uploaded_by = ?::uuid",
                w.centralId(), w.keeper().membershipId().toString()).get(0)).isEqualTo("10");
        JsonNode p = problem(call(HttpMethod.POST, importsUrl(w.centralId()), w.keeper().auth(), js(startBody(UUID.randomUUID(), 1))), 409);
        assertType(p, "import-limit");
        assertThat(p.get("detail").asText()).isEqualTo(LIMIT_TEXT);
        assertThat(values(w.clinic(), "select count(*)::text from pharmacy.opening_stock_import where store_id = ?::uuid and uploaded_by = ?::uuid",
                w.centralId(), w.keeper().membershipId().toString()).get(0)).as("nothing written").isEqualTo("10");
        assertThat(statusOf(w, last)).as("the refusal did not replace the current draft").isEqualTo("DRAFT");
        // a clientKey replay is not a new start
        JsonNode replay = ok(HttpMethod.POST, importsUrl(w.centralId()), w.keeper().auth(), js(startBody(UUID.fromString(keys.get(9)), 1)), 200);
        assertThat(idOf(replay)).isEqualTo(last);
        // the limit is for one person at one store: another person, and the same person at another store, are not limited
        Pn keeper2 = crew(w.clinic(), "PHARMACIST", "Second Pharmacist");
        mapStaff(w.clinic(), w.centralId(), keeper2, "CENTRAL_STORE_USER");
        ok(HttpMethod.POST, importsUrl(w.centralId()), keeper2.auth(), js(startBody(UUID.randomUUID(), 1)), 201);
        mapStaff(w.clinic(), w.pharmacyId(), w.keeper(), "STORE_USER");
        ok(HttpMethod.POST, importsUrl(w.pharmacyId()), w.keeper().auth(), js(startBody(UUID.randomUUID(), 1)), 201);
    }

    @Test
    void theTenthStartIsStillFineAndTheLimitAlsoCountsTheOwnersOwnStarts() throws Exception {
        World w = world();
        for (int i = 1; i <= 10; i++) {
            ok(HttpMethod.POST, importsUrl(w.centralId()), w.clinic().owner(), js(startBody(UUID.randomUUID(), 1)), 201);
        }
        assertType(problem(call(HttpMethod.POST, importsUrl(w.centralId()), w.clinic().owner(), js(startBody(UUID.randomUUID(), 1))), 409), "import-limit");
    }

    @Test
    void parallelStartsOfOnePersonCannotPassTheLimitTogether() throws Exception {
        World w = world();
        for (int i = 1; i <= 8; i++) {
            ok(HttpMethod.POST, importsUrl(w.centralId()), w.keeper().auth(), js(startBody(UUID.randomUUID(), 1)), 201);
        }
        List<RequestPostProcessor> callers = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            callers.add(w.keeper().auth());
            bodies.add(js(startBody(UUID.randomUUID(), 1)));
        }
        List<String> answers = atTheSameMoment(HttpMethod.POST, importsUrl(w.centralId()), callers, bodies);
        assertThat(statusCount(answers, 201)).as(answers.toString()).isEqualTo(2);
        assertThat(statusCount(answers, 409)).isEqualTo(4);
        assertThat(values(w.clinic(), "select count(*)::text from pharmacy.opening_stock_import where store_id = ?::uuid and uploaded_by = ?::uuid",
                w.centralId(), w.keeper().membershipId().toString()).get(0)).isEqualTo("10");
        assertThat(values(w.clinic(), "select count(*)::text from pharmacy.opening_stock_import where store_id = ?::uuid and status = 'DRAFT'", w.centralId()).get(0))
                .as("one draft at a time").isEqualTo("1");
    }

    // ---------------- DEC-077 item 1: a store switched off meanwhile answers 404 ----------------

    private void switchOffByDatabase(World w, String storeId) throws Exception {
        commitAsTenant(w.clinic().tenantId(), "update pharmacy.store set status = 'INACTIVE' where id = ?::uuid", storeId);
    }

    @Test
    void startSubmitAndApproveAnswer404ForAStoreSwitchedOffMeanwhileAndNothingIsWritten() throws Exception {
        World w = world();
        // submit: a draft whose store is switched off after the lines arrived
        String draft = idOf(draftOfCleanFile(w.pharmacyKeeper().auth(), "PHARM", w.pharmacyId(), w.codeA(), w.codeB()));
        switchOffByDatabase(w, w.pharmacyId());
        assertType(problem(call(HttpMethod.POST, importUrl(w.pharmacyId(), draft) + "/submit", w.pharmacyKeeper().auth(), null), 404), "not-found");
        assertThat(statusOf(w, draft)).as("submit wrote nothing").isEqualTo("DRAFT");
        // start: the store is not active
        assertType(problem(call(HttpMethod.POST, importsUrl(w.pharmacyId()), w.pharmacyKeeper().auth(), js(startBody(UUID.randomUUID(), 1))), 404), "not-found");
        assertType(problem(call(HttpMethod.POST, importsUrl(w.pharmacyId()), w.clinic().owner(), js(startBody(UUID.randomUUID(), 1))), 404), "not-found");
        assertThat(importStatuses(w.clinic(), w.pharmacyId())).as("no new import").containsExactly("DRAFT");
        // approve: a waiting import whose store is switched off before the approval
        World v = world();
        String waiting = idOf(draftOfCleanFile(v.pharmacyKeeper().auth(), "PHARM", v.pharmacyId(), v.codeA(), v.codeB()));
        submit(v.pharmacyKeeper().auth(), v.pharmacyId(), waiting);
        switchOffByDatabase(v, v.pharmacyId());
        assertType(problem(call(HttpMethod.POST, importUrl(v.pharmacyId(), waiting) + "/approve", v.second().auth(), null), 404), "not-found");
        assertThat(statusOf(v, waiting)).as("approve wrote nothing").isEqualTo("WAITING_APPROVAL");
        assertThat(values(v.clinic(), "select has_stock::text from pharmacy.store where id = ?::uuid", v.pharmacyId()).get(0)).isEqualTo("false");
        assertThat(count(v.clinic(), "select count(*) from pharmacy.product where has_stock")).as("no product got stock").isZero();
    }

    @Test
    void theDatabaseRefusesAnImportForAStoreThatIsNotActive() throws Exception {
        World w = world();
        switchOffByDatabase(w, w.pharmacyId());
        String unit = values(w.clinic(), "select id::text from pharmacy.unit").get(0);
        db().inTenant(w.clinic().tenantId(), c -> {
            SQLException e = refused(c, "insert into pharmacy.opening_stock_import (unit_id, store_id, status, row_count, uploaded_by, client_key, created_by) "
                    + "values (?::uuid, ?::uuid, 'DRAFT', 1, ?::uuid, ?::uuid, ?::uuid)", unit, w.pharmacyId(), w.keeper().membershipId().toString(), UUID.randomUUID().toString(),
                    w.keeper().membershipId().toString());
            thatRefusal(e).as("the insert trigger of the hardening migration").isNotNull();
            assertThat(e.getSQLState()).startsWith("23");
            // the same insert for an active store is fine
            thatRefusal(refused(c, "insert into pharmacy.opening_stock_import (unit_id, store_id, status, row_count, uploaded_by, client_key, created_by) "
                    + "values (?::uuid, ?::uuid, 'DRAFT', 1, ?::uuid, ?::uuid, ?::uuid)", unit, w.centralId(), w.keeper().membershipId().toString(), UUID.randomUUID().toString(),
                    w.keeper().membershipId().toString())).isNull();
            return null;
        });
    }

    private List<String> inParallel(List<Callable<String>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        try {
            CountDownLatch ready = new CountDownLatch(work.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> job : work) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return job.call();
                }));
            }
            ready.await();
            go.countDown();
            List<String> out = new ArrayList<>();
            for (Future<String> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private String status(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        var response = actions.andReturn().getResponse();
        return response.getStatus() + "|" + response.getContentAsString();
    }

    @Test
    void aSwitchOffAndAStartAtTheSameMomentEndWithOneWinnerAndNeverAnImportOnASwitchedOffStore() throws Exception {
        World w = world();
        for (int round = 1; round <= 6; round++) {
            String storeId = idOf(createStore(w.clinic(), "Sub Store " + round, "SUB" + round, "SUB_STORE"));
            List<String> answers = inParallel(List.of(
                    () -> status(call(HttpMethod.POST, STORES + "/" + storeId + "/switch-off", w.clinic().owner(), null)),
                    () -> status(call(HttpMethod.POST, importsUrl(storeId), w.clinic().owner(), js(startBody(UUID.randomUUID(), 1))))));
            int off = Integer.parseInt(answers.get(0).substring(0, 3));
            int start = Integer.parseInt(answers.get(1).substring(0, 3));
            assertThat(List.of(off, start)).as("round " + round + ": " + answers).isIn(List.of(200, 404), List.of(409, 201));
            if (off == 409) {
                assertThat(answers.get(0)).contains("open-import");
            }
            assertThat(count(w.clinic(), "select count(*) from pharmacy.opening_stock_import i join pharmacy.store s on s.tenant_id = i.tenant_id and s.id = i.store_id "
                    + "where s.id = ?::uuid and s.status <> 'ACTIVE'", UUID.fromString(storeId))).as("round " + round + ": no import on a switched-off store").isZero();
        }
        assertThat(count(w.clinic(), "select count(*) from pharmacy.opening_stock_import i join pharmacy.store s on s.tenant_id = i.tenant_id and s.id = i.store_id "
                + "where s.status <> 'ACTIVE'")).isZero();
    }

    // ---------------- DEC-077 item 1: the product lock ----------------

    @Test
    void aProductEditAndALineWriteAtTheSameMomentEndWithOneWinnerThroughTheProductLock() throws Exception {
        for (int round = 1; round <= 5; round++) {
            World w = world();
            String id = idOf(startImport(w.keeper().auth(), w.centralId(), 1));
            JsonNode product = getProduct(w.clinic(), w.ownId());
            Map<String, Object> edit = updateBody(product);
            edit.put("issueUnit", "strip");
            List<String> answers = inParallel(List.of(
                    () -> status(call(HttpMethod.PUT, productUrl(w.ownId()), w.clinic().owner(), js(edit))),
                    () -> status(call(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(List.of(row(1, "CS", w.codeB(), "LOCK1", farExpiry(), "10", "5", "1"))))))));
            int editStatus = Integer.parseInt(answers.get(0).substring(0, 3));
            int lineStatus = Integer.parseInt(answers.get(1).substring(0, 3));
            // the line write takes the product share lock; the edit locks the product row before its open-import check:
            // either the edit goes first (200, then the line reads the edited product) or the line goes first (the edit sees the open import: 409)
            assertThat(List.of(editStatus, lineStatus)).as("round " + round + ": " + answers).isIn(List.of(200, 200), List.of(409, 200));
            if (editStatus == 409) {
                assertThat(answers.get(0)).contains("open-import");
                assertThat(getProduct(w.clinic(), w.ownId()).get("issueUnit").asText()).as("the edit was refused").isEqualTo("piece");
            } else {
                assertThat(getProduct(w.clinic(), w.ownId()).get("issueUnit").asText()).isEqualTo("strip");
            }
        }
    }

    @Test
    void submitFlagsALineWhoseProductIsNoLongerActiveAsAnErrorProductUnknown() throws Exception {
        World w = world();
        String id = idOf(draftOfCleanFile(w.keeper().auth(), "CS", w.centralId(), w.codeA(), w.codeB()));
        // the product goes off between the line write and the submit (a direct write stands for the lost race)
        commitAsTenant(w.clinic().tenantId(), "update pharmacy.product set status = 'INACTIVE' where id = ?::uuid", w.ownId());
        JsonNode p = problem(call(HttpMethod.POST, importUrl(w.centralId(), id) + "/submit", w.keeper().auth(), null), 422);
        assertType(p, "opening-stock-invalid");
        boolean flagged = false;
        for (JsonNode issue : p.get("rowIssues")) {
            if ("product-unknown".equals(issue.get("code").asText()) && "ERROR".equals(issue.get("severity").asText())) {
                flagged = true;
                assertThat(issue.get("rowNo").asInt()).isEqualTo(2);
            }
        }
        assertThat(flagged).as("an ERROR product-unknown row: " + p).isTrue();
        assertThat(statusOf(w, id)).isEqualTo("NOT_ACCEPTED");
    }

    @Test
    void anImportForAStoreOfAnotherClinicIsStill404AndTheHardeningChangedNothingForTheWall() throws Exception {
        World a = world();
        World b = world();
        String idA = idOf(startImport(a.keeper().auth(), a.centralId(), 1));
        assertType(problem(call(HttpMethod.POST, importUrl(a.centralId(), idA) + "/reject", b.second().auth(), REJECT), 404), "not-found");
        assertThat(statusOf(a, idA)).isEqualTo("DRAFT");
        assertType(problem(call(HttpMethod.POST, importsUrl(a.centralId()), b.keeper().auth(), js(startBody(UUID.randomUUID(), 1))), 404), "not-found");
    }
}
