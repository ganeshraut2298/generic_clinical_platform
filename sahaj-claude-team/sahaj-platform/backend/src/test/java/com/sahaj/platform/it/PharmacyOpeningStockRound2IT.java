package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Review round 2 of SAH-78-7 (DEC-071): security L8 (an unchanged resend writes nothing; at most 2 x ceil(rowCount / 200) line
 * requests answered 200 per import, counted under the import row lock in {@code line_request_count}, migration 1260; replaced by the cap on changed rows written, {@code line_rows_written}, contract 0.2.1: the cap tests are now in PharmacyHardeningImportIT),
 * security I4 (the uploader and decider columns hold the caller's MEMBERSHIP id, not the user id) and the N-13 template
 * placeholder. Contract docs/api/pharmacy.yaml 0.1.3. Test plan: docs/test-plans/SAH-78.md (T78-L8-*, T78-I4-*).
 */
@OnTestDatabase
class PharmacyOpeningStockRound2IT extends Sah78BSupport {

    private static final String STATE_TEXT = "This import can no longer be changed.";

    private record World(Clinic clinic, JsonNode central, Pn keeper, Pn second, String codeA) {

        String centralId() {
            return central.get("id").asText();
        }
    }

    private World world() throws Exception {
        Clinic clinic = pharmacyClinic();
        JsonNode[] stores = centralAndPharmacy(clinic);
        Pn keeper = crew(clinic, "PHARMACIST", "Pia Pharmacist");
        mapStaff(clinic, idOf(stores[0]), keeper, "CENTRAL_STORE_USER");
        Pn second = crew(clinic, "OWNER", "Second Owner");
        adopt(clinic, "G-000101");
        return new World(clinic, stores[0], keeper, second, "G-000101");
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

    private String counter(World w, String importId) throws Exception {
        return values(w.clinic(), "select line_rows_written::text from pharmacy.opening_stock_import where id = ?::uuid", importId).get(0);
    }

    /** row_no:version:updated_at of every line, in row order. */
    private String lineSnapshot(World w, String importId) throws Exception {
        return values(w.clinic(), "select coalesce(string_agg(row_no || ':' || version || ':' || coalesce(updated_at::text, '-') || ':' || batch_raw, ',' "
                + "order by row_no), '') from pharmacy.opening_stock_line where import_id = ?::uuid", importId).get(0);
    }

    /** Number of issue rows / of them superseded / their ids, for the whole import. */
    private String issueSnapshot(World w, String importId) throws Exception {
        return values(w.clinic(), "select count(*) || '/' || count(*) filter (where i.superseded) || '/' || coalesce(string_agg(i.id::text, ',' order by i.id), '') "
                + "from pharmacy.opening_stock_line_issue i join pharmacy.opening_stock_line l on l.tenant_id = i.tenant_id and l.id = i.line_id "
                + "where l.import_id = ?::uuid", importId).get(0);
    }

    private String issueCounts(World w, String importId) throws Exception {
        String snap = issueSnapshot(w, importId);
        return snap.substring(0, snap.lastIndexOf('/'));
    }

    // ---------------- L8(a): an unchanged resend writes nothing ----------------

    @Test
    void anUnchangedResendWritesNothingAndAnswersTheSameIssuesWhileAChangedCellIsWritten() throws Exception {
        World w = world();
        String id = idOf(startImport(w.keeper().auth(), w.centralId(), 250)); // cap 500 changed rows, only 3 rows are sent
        List<Map<String, Object>> three = List.of(
                row(1, "CS", w.codeA(), "TESTB001", farExpiry(), "45.50", "30.25", "100"),   // clean
                row(2, "CS", w.codeA(), "TESTB002", farExpiry(), "20", "12.5", "x"),         // ERROR quantity-invalid
                row(3, "CS", w.codeA(), "TESTB003", farExpiry(), "10", "50", "3"));          // WARNING rate-above-mrp
        JsonNode first = ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(three)), 200);
        assertThat(first.get("issues")).as("an error and a warning on rows 2 and 3").hasSize(2);
        String lines = lineSnapshot(w, id);
        String issues = issueSnapshot(w, id);
        assertThat(issues).startsWith("2/0/");
        assertThat(counter(w, id)).isEqualTo("3");

        // the same seven cells again: nothing is written, the same issues are answered
        JsonNode again = ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(three)), 200);
        assertThat(again.get("issues")).as("the stored issues are answered again").isEqualTo(first.get("issues"));
        assertThat(again.get("receivedRows").asInt()).isEqualTo(3);
        assertThat(lineSnapshot(w, id)).as("line version and updated_at unchanged").isEqualTo(lines);
        assertThat(issueSnapshot(w, id)).as("no new issue rows and none newly superseded, the same ids").isEqualTo(issues);
        assertThat(counter(w, id)).as("an unchanged request writes nothing and is not counted (0.2.1)").isEqualTo("3");
        assertThat(getImport(w.keeper().auth(), w.centralId(), id).get("errorCount").asInt()).isOne();

        // a request that mixes an unchanged row (3) and a changed one (2: quantity fixed): only row 2 is written
        List<Map<String, Object>> mixed = List.of(
                row(2, "CS", w.codeA(), "TESTB002", farExpiry(), "20", "12.5", "4"),
                three.get(2));
        JsonNode mixedResult = ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(mixed)), 200);
        assertThat(mixedResult.get("issues")).as("row 2 is clean now, row 3 keeps its warning").hasSize(1);
        assertThat(mixedResult.get("issues").get(0).get("rowNo").asInt()).isEqualTo(3);
        assertThat(mixedResult.get("issues").get(0).get("code").asText()).isEqualTo("rate-above-mrp");
        String afterMixed = lineSnapshot(w, id);
        String[] before = lines.split(",");
        String[] after = afterMixed.split(",");
        assertThat(after[0]).as("row 1 untouched").isEqualTo(before[0]);
        assertThat(after[2]).as("row 3 untouched").isEqualTo(before[2]);
        assertThat(after[1]).as("row 2 written: version up, updated_at set").isNotEqualTo(before[1]).startsWith("2:1:");
        // the old error is superseded (one more superseded row), the warning of row 3 is the same row as before
        String[] issueNow = issueSnapshot(w, id).split("/");
        assertThat(issueNow[0] + "/" + issueNow[1]).as("2 issue rows from the first send, the error of row 2 superseded").isEqualTo("2/1");
        assertThat(getImport(w.keeper().auth(), w.centralId(), id).get("errorCount").asInt()).isZero();
        assertThat(counter(w, id)).isEqualTo("4");

        // any one cell different is a change, also only the letter case of the product code
        List<Map<String, Object>> caseOnly = List.of(row(1, "CS", w.codeA().toLowerCase(), "TESTB001", farExpiry(), "45.50", "30.25", "100"));
        ok(HttpMethod.POST, linesUrl(w, id), w.keeper().auth(), js(body(caseOnly)), 200);
        assertThat(lineSnapshot(w, id).split(",")[0]).as("row 1 written for a different cell").startsWith("1:1:");
        assertThat(counter(w, id)).isEqualTo("5");
        // and the log of that import holds no file text
        assertThat(auditRowsOf(w.clinic(), "PHARMACY_OPENING_STOCK_STARTED")).hasSize(1);
    }

    // ---------------- I4: membership id, not user id ----------------

    @Test
    void uploadedByAndDecidedByAreTheMembershipIdOfTheCallerNeverTheUserId() throws Exception {
        World w = world();
        assertThat(w.keeper().membershipId()).as("the fixture is meaningful: membership and user ids differ").isNotEqualTo(w.keeper().userId());
        assertThat(w.second().membershipId()).isNotEqualTo(w.second().userId());
        // start: the keeper uploads
        JsonNode started = draftOfCleanFile(w.keeper().auth(), "CS", w.centralId(), w.codeA(), w.codeA());
        String id = idOf(started);
        assertThat(columns(w, id)).containsExactly(w.keeper().membershipId().toString(), w.keeper().membershipId().toString(), null);
        assertThat(started.get("uploadedBy").get("membershipId").asText()).isEqualTo(w.keeper().membershipId().toString());
        // submit
        submit(w.keeper().auth(), w.centralId(), id);
        assertThat(columns(w, id)).containsExactly(w.keeper().membershipId().toString(), w.keeper().membershipId().toString(), null);
        // approve by the second owner: decided_by is that owner's membership id, uploaded_by is unchanged
        JsonNode approved = ok(HttpMethod.POST, importUrl(w.centralId(), id) + "/approve", w.second().auth(), null, 200);
        assertThat(columns(w, id)).containsExactly(w.keeper().membershipId().toString(), w.keeper().membershipId().toString(),
                w.second().membershipId().toString());
        assertThat(approved.get("decidedBy").get("membershipId").asText()).isEqualTo(w.second().membershipId().toString());
        assertThat(columns(w, id)).doesNotContain(w.keeper().userId().toString(), w.second().userId().toString());

        // reject: a new import by the owner (the first owner), rejected by the second owner
        World r = world();
        String ownerImport = idOf(draftOfCleanFile(r.clinic().owner(), "CS", r.centralId(), r.codeA(), r.codeA()));
        submit(r.clinic().owner(), r.centralId(), ownerImport);
        String ownerMembership = contextOf(r.clinic().owner()).get("caller").get("membershipId").asText();
        assertThat(ownerMembership).isNotEqualTo(r.clinic().ownerUserId().toString());
        assertThat(columns(r, ownerImport).get(0)).as("the owner's upload is stored with the owner's membership id").isEqualTo(ownerMembership);
        ok(HttpMethod.POST, importUrl(r.centralId(), ownerImport) + "/reject", r.second().auth(), "{\"reasonKey\":\"WRONG_FILE\",\"note\":null}", 200);
        assertThat(columns(r, ownerImport)).containsExactly(ownerMembership, ownerMembership, r.second().membershipId().toString());
        // the uploader-owner rejecting his own file: decided_by is his membership id, not his user id
        String mine = idOf(draftOfCleanFile(r.clinic().owner(), "CS", r.centralId(), r.codeA(), r.codeA()));
        submit(r.clinic().owner(), r.centralId(), mine);
        ok(HttpMethod.POST, importUrl(r.centralId(), mine) + "/reject", r.clinic().owner(), "{\"reasonKey\":\"WRONG_STORE\",\"note\":null}", 200);
        assertThat(columns(r, mine).get(2)).isEqualTo(ownerMembership);
        // every membership id written exists as a membership of this clinic (and no user id is among them)
        assertThat(count(r.clinic(), "select count(*) from pharmacy.opening_stock_import i where not exists (select 1 from platform.membership m "
                + "where m.id = i.uploaded_by) or (i.decided_by is not null and not exists (select 1 from platform.membership m where m.id = i.decided_by))"))
                .isZero();
    }

    /** uploaded_by, created_by, decided_by of one import (null when empty). */
    private List<String> columns(World w, String importId) throws SQLException {
        return db().inTenant(w.clinic().tenantId(), c -> {
            try (var ps = c.prepareStatement("select uploaded_by::text, created_by::text, decided_by::text from pharmacy.opening_stock_import where id = ?::uuid")) {
                ps.setString(1, importId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    List<String> out = new ArrayList<>();
                    for (int i = 1; i <= 3; i++) {
                        out.add(rs.getString(i));
                    }
                    return out;
                }
            }
        });
    }

    // ---------------- N-13: the template ----------------

    @Test
    void theTemplateExampleRowUsesThePlaceholderNotASampleCatalogueCode() throws Exception {
        World w = world();
        for (RequestPostProcessor who : List.of(w.clinic().owner(), w.keeper().auth())) {
            MvcResult r = mvc.perform(get(TEMPLATE).with(who)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentType()).startsWith("text/csv");
            String text = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(text).startsWith("store code,product code,batch,expiry date,MRP,purchase rate,quantity\n#EXAMPLE,PRODUCT-CODE,");
            assertThat(text).doesNotContain("G-0001");
        }
        assertThat(mvc.perform(get(TEMPLATE).with(w.clinic().owner()).accept(MediaType.APPLICATION_JSON)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
