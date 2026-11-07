package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Shared set-up of the SAH-35 (Medicine round) and SAH-36 (Admitted patient cards) integration tests. The clock seam is the one
 * of {@link MarSupport}, which now also moves {@code EscalationBoardRepository.databaseNow()} (the Safety board, its summary and
 * the Admitted list read their {@code asOf} there, ADR-012 D1, ADR-013 D2), so the board, the MAR tab count and the dose endpoint
 * all see the one fixed instant of {@link #clockAt}. No bean override is added here: every extra override makes a new Spring
 * context with its own connection pool of 10. It also holds the change token worked out here from the rule of ADR-012
 * D3 (never read from the application), a second admitted patient for a ward, and bulk ACTIVE orders written by the table
 * owner for the volume cases (the 200-row cap, the query plans).
 */
abstract class RoundSupport extends MarSupport {

    // ---------------- urls ----------------

    static final String ADMITTED_LIST = IPD + "/admissions";

    // ---------------- more patients in one ward ----------------

    /** Another admitted patient of the same hospital, with the same staff. */
    protected Ward anotherPatient(Ward w) throws Exception {
        Adm adm = admitWithAllergy(w.clinic(), "None known");
        String uhid = values(w.clinic(), "select uhid from patient.patient where id = ?::uuid", adm.patientId()).get(0);
        return new Ward(w.clinic(), w.doctor(), w.nurse(), w.nurse2(), w.nurse2Pin(), w.pharmacist(), w.pharmacistPin(), adm, uhid);
    }

    // ---------------- the change token, worked out here ----------------

    /** SHA-256 of the sorted lines joined by a line feed, first 16 bytes, base64url without padding (ADR-007 R1). */
    protected static String tokenOfLines(List<String> lines) throws Exception {
        List<String> sorted = new ArrayList<>(lines);
        Collections.sort(sorted);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", sorted).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 16));
    }

    /** ADR-012 D3: {@code dose:<order id lower case>:<slotAt as epoch seconds>:<LATE|DUE>}. */
    protected static String doseLine(String orderId, Instant slot, String state) {
        return "dose:" + orderId.toLowerCase(java.util.Locale.ROOT) + ":" + slot.getEpochSecond() + ":" + state;
    }

    /** The escalation lines of the clinic (ADR-007 R1) read by SQL, for a token that holds both kinds of line. */
    protected List<String> escalationLines(Clinic clinic) throws SQLException {
        return new ArrayList<>(column(clinic, "select lower(e.id::text) || ':' || e.version::text "
                + "from ipd.ews_escalation e join ipd.admission a on a.tenant_id = e.tenant_id and a.id = e.admission_id "
                + "where e.status in ('OPEN', 'ACK') and a.status = 'ADMITTED'"));
    }

    // ---------------- reading the board ----------------

    protected static JsonNode roundRow(JsonNode board, String orderId, Instant slot) {
        for (JsonNode r : board.get("medicineRound").get("rows")) {
            if (orderId.equals(r.get("orderId").asText()) && slot.equals(Instant.parse(r.get("slotAt").asText()))) {
                return r;
            }
        }
        return null;
    }

    protected static List<String> roundKeys(JsonNode board) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : board.get("medicineRound").get("rows")) {
            out.add(r.get("orderId").asText() + "@" + Instant.parse(r.get("slotAt").asText()).getEpochSecond());
        }
        return out;
    }

    // ---------------- bulk ACTIVE orders for the volume cases ----------------

    private static final String[] BULK_DRUGS = {"pcm", "panto", "ondan", "ns", "ceft", "amox", "pip", "cotri", "asp", "ibu", "met",
        "hep", "ins", "kcl"};

    /**
     * Writes {@code count} ACTIVE orders (distinct drugs, at most 14) for one admission as the table owner, committed, with
     * the order insert guard switched off inside the transaction: placed at 06:00 and checked at 07:00 IST the day before
     * the test's date, so no slot of today is "before the order started". The application cannot write an ACTIVE order
     * directly; the two columns of the staff are taken from the ward. Returns the order ids.
     */
    protected List<String> bulkActiveOrders(Ward w, int count, String frequency) throws SQLException {
        assertThat(count).isBetween(1, BULK_DRUGS.length);
        UUID orderedBy = w.doctor().userId();
        UUID verifiedBy = w.pharmacist().userId();
        Instant ordered = istOn(day.minusDays(1), 6, 0);
        Instant verified = istOn(day.minusDays(1), 7, 0);
        return asOwner(true, new String[] {"ipd.medicine_order", "ipd.admission"}, c -> {
            DbAccess.execute(c, "alter table ipd.medicine_order disable trigger trg_medicine_order_guard_insert");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                try (PreparedStatement ps = c.prepareStatement("insert into ipd.medicine_order (tenant_id, admission_id, client_key, "
                        + "drug_key, frequency_key, status, ordered_by, ordered_at, verified_by, verified_at) "
                        + "values (?::uuid, ?::uuid, gen_random_uuid(), ?, ?, 'ACTIVE', ?::uuid, ?, ?::uuid, ?) returning id::text")) {
                    ps.setString(1, w.clinic().tenantId().toString());
                    ps.setString(2, w.adm().id());
                    ps.setString(3, BULK_DRUGS[i]);
                    ps.setString(4, frequency);
                    ps.setString(5, orderedBy.toString());
                    ps.setTimestamp(6, Timestamp.from(ordered));
                    ps.setString(7, verifiedBy.toString());
                    ps.setTimestamp(8, Timestamp.from(verified));
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        ids.add(rs.getString(1));
                    }
                }
            }
            DbAccess.execute(c, "alter table ipd.medicine_order enable trigger trg_medicine_order_guard_insert");
            return ids;
        });
    }
}
