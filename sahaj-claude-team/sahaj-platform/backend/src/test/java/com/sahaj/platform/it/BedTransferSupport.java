package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared set-up of the SAH-26 integration tests (bed transfer, transfer rules): moves through the real API, the
 * expected bill lines worked out independently of the application code, bed and admission state read back by SQL,
 * and the backdating of admissions (see {@link IpdBillingSupport#backdate}) so that a move happens on bill day 3.
 */
abstract class BedTransferSupport extends IpdBillingSupport {

    static final String TRANSFER_RULES = IPD + "/transfer-rules";
    static final String OPTIONS = IPD + "/admission-options";

    static final String BED_TAKEN = "That bed is no longer free. Pick another.";
    static final String SAME_BED = "The patient is already in this bed. Pick another.";
    static final String NOT_ADMITTED = "This patient is not admitted any more. The patient cannot be moved.";

    static final String[] MOVERS = CHARGERS;
    static final String[] NON_MOVERS = NON_CHARGERS;

    /** Default daily bed charge, visit charge x multiplier, name and minimum deposit per category (paise). */
    static final Map<String, Long> BED = Map.of("gen", 80_000L, "semi", 150_000L, "pvt", 250_000L, "icu", 500_000L);
    static final Map<String, Long> VISIT_UNIT = Map.of("gen", 50_000L, "semi", 62_500L, "pvt", 75_000L, "icu", 100_000L);
    static final Map<String, String> NAME = Map.of("gen", "General", "semi", "Semi-private", "pvt", "Private",
            "icu", "ICU");
    static final Map<String, Long> MIN_DEPOSIT = Map.of("gen", 1_000_000L, "semi", 1_200_000L, "pvt", 1_500_000L,
            "icu", 2_000_000L);
    static final String DOCTOR_LINE = "Doctor visits · Dr Asha";

    // ---------------- expected bill lines (written from the story, not from the code) ----------------

    /** One expected bill line. */
    protected record L(String name, long unit, int qty, boolean segment) {
    }

    /** The two lines of a bed the patient left; none when the segment has no days. */
    protected static L[] before(String category, int days) {
        if (days <= 0) {
            return new L[0];
        }
        return new L[] {new L("Bed · " + NAME.get(category) + " (before transfer)", BED.get(category), days, true),
                new L("Doctor visits · " + NAME.get(category) + " rate", VISIT_UNIT.get(category), days, true)};
    }

    /** The two lines of the bed the patient is in; none when no day is left on it. */
    protected static L[] current(String category, int days) {
        if (days <= 0) {
            return new L[0];
        }
        return new L[] {new L("Bed · " + NAME.get(category), BED.get(category), days, false),
                new L(DOCTOR_LINE, VISIT_UNIT.get(category), days, false)};
    }

    protected static L nursing(int days) {
        return new L("Nursing care", NURSING, days, false);
    }

    /** Asserts the exact lines, their order, the segment marker, unit x quantity and the total. */
    protected static void assertBill(JsonNode bill, Object... parts) {
        List<L> expected = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof L l) {
                expected.add(l);
            } else if (part instanceof L[] many) {
                expected.addAll(List.of(many));
            } else {
                throw new IllegalArgumentException("not a bill line: " + part);
            }
        }
        List<String> names = new ArrayList<>();
        expected.forEach(l -> names.add(l.name()));
        assertThat(lineNames(bill)).as("bill line names in order").containsExactlyElementsOf(names);
        long total = 0;
        for (int i = 0; i < expected.size(); i++) {
            L want = expected.get(i);
            JsonNode got = bill.get("lines").get(i);
            assertThat(got.get("unitPaise").asLong()).as(want.name() + " unit").isEqualTo(want.unit());
            assertThat(got.get("qty").asInt()).as(want.name() + " qty").isEqualTo(want.qty());
            assertThat(got.get("amountPaise").asLong()).as(want.name() + " amount").isEqualTo(want.unit() * want.qty());
            assertThat(got.path("segment").asBoolean(false)).as(want.name() + " segment marker").isEqualTo(want.segment());
            total += want.unit() * want.qty();
        }
        assertThat(bill.get("totalPaise").asLong()).as("bill total").isEqualTo(total);
        assertBillAddsUp(bill);
    }

    protected static long total(Object... parts) {
        long total = 0;
        for (Object part : parts) {
            if (part instanceof L l) {
                total += l.unit() * l.qty();
            } else {
                for (L l : (L[]) part) {
                    total += l.unit() * l.qty();
                }
            }
        }
        return total;
    }

    // ---------------- moves ----------------

    protected static String moveBody(Object toBedId, String reason) {
        return "{\"toBedId\":" + (toBedId == null ? "null" : "\"" + toBedId + "\"")
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
    }

    protected String transferUrl(Object admissionId) {
        return ADMISSIONS + "/" + admissionId + "/transfer";
    }

    protected ResultActions move(Clinic clinic, RequestPostProcessor auth, Object admissionId, Object toBedId)
            throws Exception {
        return call(HttpMethod.POST, transferUrl(admissionId), auth, moveBody(toBedId, null));
    }

    protected ResultActions moveAs(Clinic clinic, String role, Object admissionId, Object toBedId) throws Exception {
        return move(clinic, clinic.as(role), admissionId, toBedId);
    }

    /** A successful move by the owner; returns the admission sheet. */
    protected JsonNode moveOk(Clinic clinic, Object admissionId, Object toBedId) throws Exception {
        return ok(HttpMethod.POST, transferUrl(admissionId), clinic.owner(), moveBody(toBedId, null), 200);
    }

    protected JsonNode moveOk(Clinic clinic, Object admissionId, Object toBedId, String reason) throws Exception {
        return ok(HttpMethod.POST, transferUrl(admissionId), clinic.owner(), moveBody(toBedId, reason), 200);
    }

    protected void setRules(Clinic clinic, boolean upgradeNeedsOwner, String newRateFrom) throws Exception {
        ok(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(),
                "{\"upgradeNeedsOwner\":" + upgradeNeedsOwner + ",\"newRateFrom\":\"" + newRateFrom + "\"}", 200);
    }

    // ---------------- admissions at a bill day ----------------

    /** Moves the admission time so that the bill has {@code days} days under the cut in force. */
    protected void backdateToBillDays(Clinic clinic, String admissionId, int days, String cut) throws Exception {
        Instant at = "24h".equals(cut)
                ? Instant.now().minus(days * 24L, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES)
                : istMidnight(istToday().minusDays(days - 1L)).plus(1, ChronoUnit.HOURS);
        backdate(clinic, admissionId, at);
    }

    /** A Self patient in a new bed of the category with the minimum deposit, at the given bill day. */
    protected JsonNode admitAtDay(Clinic clinic, String category, int days, String cut) throws Exception {
        JsonNode a = admit(clinic, category, "Self", MIN_DEPOSIT.get(category));
        if (days > 1 || "24h".equals(cut)) {
            backdateToBillDays(clinic, a.get("id").asText(), days, cut);
        }
        return a;
    }

    // ---------------- state read by SQL ----------------

    protected UUID bedOf(Clinic clinic, String admissionId) throws SQLException {
        return UUID.fromString(values(clinic, "select bed_id::text from ipd.admission where id = ?::uuid", admissionId)
                .get(0));
    }

    protected String bedStatus(Clinic clinic, UUID bed) throws SQLException {
        return values(clinic, "select status from ipd.bed where id = ?", bed).get(0);
    }

    protected long bedVersion(Clinic clinic, UUID bed) throws SQLException {
        return count(clinic, "select version from ipd.bed where id = ?", bed);
    }

    protected long admissionVersion(Clinic clinic, String admissionId) throws SQLException {
        return count(clinic, "select version from ipd.admission where id = ?::uuid", admissionId);
    }

    protected void setBedStatus(Clinic clinic, UUID bed, String status) throws SQLException {
        commitAsTenant(clinic.tenantId(), "update ipd.bed set status = ? where id = ?", status, bed);
    }

    protected long transferRows(Clinic clinic) throws SQLException {
        return rows(clinic, "bed_transfer");
    }

    /** Stored segment days of the admission's transfers, oldest first. */
    protected List<String> segmentDays(Clinic clinic, String admissionId) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select segment_days::text from ipd.bed_transfer where admission_id = ?::uuid "
                    + "order by transferred_at, id")) {
                ps.setString(1, admissionId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    /** Free bed ids offered by the admission options, in the order the server returns them. */
    protected List<String> offeredBeds(Clinic clinic) throws Exception {
        List<String> ids = new ArrayList<>();
        ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200).get("beds").forEach(b -> ids.add(b.get("id").asText()));
        return ids;
    }

    /** assertThat for a SQLException: it is Iterable, so the plain call is ambiguous. */
    protected static org.assertj.core.api.ObjectAssert<Object> assertError(SQLException e) {
        return assertThat((Object) e);
    }

    /** One text value read as the table owner (catalog and privilege checks). */
    protected String ownerText(String sql) throws SQLException {
        return db().asMigrator(c -> DbAccess.text(c, sql));
    }

    /** Message of the SQL error a statement raises as sahaj_app with the tenant bound; fails when it succeeds. */
    protected String deniedMessage(Clinic clinic, String sql, Object... params) {
        try {
            db().inTenant(clinic.tenantId(), c -> {
                DbAccess.execute(c, sql, params);
                return null;
            });
        } catch (SQLException e) {
            return e.getMessage();
        }
        throw new AssertionError("statement was accepted: " + sql);
    }
}
