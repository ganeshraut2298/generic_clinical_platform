package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-26 the {@code ipd.bed_transfer} table and the new grants, checked by SQL on the real {@code sahaj_test}
 * database: row level security, composite foreign keys across clinics, append-only for {@code sahaj_app} and for the
 * table owner, insertable columns, check constraints, the {@code ipd.admission} column grant, and what a bed with a
 * transfer history can no longer do. Test plan: docs/test-plans/SAH-26.md.
 */
@OnTestDatabase
class BedTransferTableIT extends BedTransferSupport {

    private static final String COLUMNS = "(admission_id, from_bed_id, to_bed_id, from_category_key, to_category_key, "
            + "segment_days, new_rate_from, reason, transferred_by)";
    private static final String INSERT = "insert into ipd.bed_transfer " + COLUMNS
            + " values (?::uuid, ?, ?, 'gen', 'gen', 0, 'same', null, ?)";

    /** A clinic with one admitted patient who has been moved once: admission, old bed, new bed. */
    private record Moved(Clinic clinic, String admissionId, UUID oldBed, UUID newBed, UUID spareBed) {
    }

    private Moved moved() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        String id = quickAdmit(clinic).get("id").asText();
        UUID oldBed = bedOf(clinic, id);
        UUID newBed = newBed(clinic, "gen");
        UUID spare = newBed(clinic, "gen");
        moveOk(clinic, id, newBed);
        return new Moved(clinic, id, oldBed, newBed, spare);
    }

    // ---------------- row level security and composite foreign keys ----------------

    @Test
    void rowsAreInvisibleWithoutATenantAndToAnotherClinicAndCannotPointAtAnotherClinicsAdmissionOrBeds() throws Exception {
        Moved a = moved();
        Moved b = moved();
        long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd.bed_transfer"));
        assertThat(unbound).as("no tenant bound").isZero();
        assertThat(rows(a.clinic(), "bed_transfer")).isOne();
        assertThat(rows(b.clinic(), "bed_transfer")).isOne();
        assertThat(count(b.clinic(), "select count(*) from ipd.bed_transfer where admission_id = ?::uuid", a.admissionId()))
                .isZero();

        // positive control: B's own admission and beds are accepted (rolled back)
        db().inTenant(b.clinic().tenantId(), c -> {
            DbAccess.execute(c, INSERT, b.admissionId(), b.newBed(), b.spareBed(), b.clinic().ownerUserId());
            return null;
        });
        // A's admission, A's beds, or a mix: the composite foreign keys (tenant_id, id) find no such pair
        assertThat(deniedMessage(b.clinic(), INSERT, a.admissionId(), b.newBed(), b.spareBed(), b.clinic().ownerUserId()))
                .contains("fk_bed_transfer_admission");
        assertThat(deniedMessage(b.clinic(), INSERT, b.admissionId(), a.newBed(), b.spareBed(), b.clinic().ownerUserId()))
                .contains("fk_bed_transfer_from_bed");
        assertThat(deniedMessage(b.clinic(), INSERT, b.admissionId(), b.newBed(), a.spareBed(), b.clinic().ownerUserId()))
                .contains("fk_bed_transfer_to_bed");
        // no tenant bound: nothing can be inserted
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, INSERT, b.admissionId(), b.newBed(), b.spareBed(), b.clinic().ownerUserId());
            return null;
        })).isInstanceOf(SQLException.class);
        // tenant_id is not an insertable column, so a foreign tenant id is refused before row level security
        assertThat(deniedMessage(b.clinic(), "insert into ipd.bed_transfer (tenant_id, admission_id, from_bed_id, to_bed_id, "
                + "from_category_key, to_category_key, segment_days, new_rate_from, transferred_by) "
                + "values (?, ?::uuid, ?, ?, 'gen', 'gen', 0, 'same', ?)", a.clinic().tenantId(), b.admissionId(),
                b.newBed(), b.spareBed(), b.clinic().ownerUserId())).contains("permission denied");
        assertThat(rows(a.clinic(), "bed_transfer")).isOne();
        assertThat(rows(b.clinic(), "bed_transfer")).isOne();
    }

    // ---------------- append-only ----------------

    @Test
    void sahajAppCannotUpdateDeleteOrTruncateAndCannotWriteIdTenantOrTime() throws Exception {
        Moved m = moved();
        Clinic c = m.clinic();
        assertThat(deniedMessage(c, "update ipd.bed_transfer set segment_days = 9")).contains("permission denied");
        assertThat(deniedMessage(c, "update ipd.bed_transfer set reason = 'Isolation' where admission_id = ?::uuid",
                m.admissionId())).contains("permission denied");
        assertThat(deniedMessage(c, "update ipd.bed_transfer set transferred_at = now() - interval '9 days'"))
                .contains("permission denied");
        assertThat(deniedMessage(c, "delete from ipd.bed_transfer")).contains("permission denied");
        assertThat(deniedMessage(c, "truncate ipd.bed_transfer")).contains("permission denied");
        assertThat(rows(c, "bed_transfer")).isOne();
        assertThat(values(c, "select segment_days::text from ipd.bed_transfer")).containsExactly("0");

        // id, tenant_id and transferred_at keep their defaults: they are not in the insert grant
        for (String column : new String[] {"id", "transferred_at"}) {
            Object value = "id".equals(column) ? UUID.randomUUID() : java.sql.Timestamp.valueOf("2020-01-01 00:00:00");
            assertThat(deniedMessage(c, "insert into ipd.bed_transfer (" + column + ", admission_id, from_bed_id, to_bed_id, "
                    + "from_category_key, to_category_key, segment_days, new_rate_from, transferred_by) "
                    + "values (?, ?::uuid, ?, ?, 'gen', 'gen', 0, 'same', ?)", value, m.admissionId(), m.newBed(),
                    m.spareBed(), c.ownerUserId())).as(column).contains("permission denied");
        }
        // positive control: the permitted columns work (rolled back), so the denials above are the grants
        db().inTenant(c.tenantId(), conn -> {
            DbAccess.execute(conn, INSERT, m.admissionId(), m.newBed(), m.spareBed(), c.ownerUserId());
            return null;
        });
        assertThat(rows(c, "bed_transfer")).isOne();
    }

    @Test
    void theTableOwnerIsStoppedByTheTriggersAndTheTableStaysForcedAndWithoutUpdateOrDeletePolicies() throws Exception {
        Moved m = moved();
        assertThatThrownBy(() -> db().asMigrator(c -> {
            DbAccess.execute(c, "truncate ipd.bed_transfer");
            return null;
        })).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> db().asMigrator(c -> {
            DbAccess.execute(c, "alter table ipd.bed_transfer no force row level security");
            DbAccess.execute(c, "update ipd.bed_transfer set segment_days = 9");
            return null;
        })).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> db().asMigrator(c -> {
            DbAccess.execute(c, "alter table ipd.bed_transfer no force row level security");
            DbAccess.execute(c, "delete from ipd.bed_transfer");
            return null;
        })).isInstanceOf(SQLException.class);
        assertThat(rows(m.clinic(), "bed_transfer")).isOne();
        assertThat(values(m.clinic(), "select segment_days::text from ipd.bed_transfer")).containsExactly("0");

        String flags = ownerText("select relrowsecurity::text || ',' || relforcerowsecurity::text "
                + "from pg_class where oid = 'ipd.bed_transfer'::regclass");
        assertThat(flags).isEqualTo("true,true");
        String policies = ownerText("select string_agg(cmd, ',' order by cmd) from pg_policies "
                + "where schemaname = 'ipd' and tablename = 'bed_transfer'");
        assertThat(policies).as("a select and an insert policy only").isEqualTo("INSERT,SELECT");
        long triggers = db().asMigrator(c -> DbAccess.count(c, "select count(*) from pg_trigger "
                + "where tgrelid = 'ipd.bed_transfer'::regclass and not tgisinternal"));
        assertThat(triggers).isEqualTo(2);
    }

    @Test
    void theGrantsAreExactlySelectAndTheNineInsertColumnsAndTheAdmissionBedColumnIsTheOnlyNewUpdate() throws Exception {
        List<String> insertable = List.of("admission_id", "from_bed_id", "to_bed_id", "from_category_key",
                "to_category_key", "segment_days", "new_rate_from", "reason", "transferred_by");
        for (String column : new String[] {"id", "tenant_id", "admission_id", "from_bed_id", "to_bed_id", "from_category_key",
                "to_category_key", "segment_days", "new_rate_from", "reason", "transferred_at", "transferred_by"}) {
            String canInsert = ownerText("select has_column_privilege('sahaj_app', 'ipd.bed_transfer', '" + column
                    + "', 'INSERT')::text");
            assertThat(canInsert).as("insert " + column).isEqualTo(String.valueOf(insertable.contains(column)));
            String canUpdate = ownerText("select has_column_privilege('sahaj_app', 'ipd.bed_transfer', '" + column
                    + "', 'UPDATE')::text");
            assertThat(canUpdate).as("update " + column).isEqualTo("false");
        }
        assertThat(ownerText("select has_table_privilege('sahaj_app', 'ipd.bed_transfer', 'SELECT')::text"))
                .isEqualTo("true");
        for (String privilege : new String[] {"DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
            assertThat(ownerText("select has_table_privilege('sahaj_app', 'ipd.bed_transfer', '" + privilege + "')::text"))
                    .as(privilege).isEqualTo("false");
        }
        assertThat(ownerText("select has_table_privilege('public', 'ipd.bed_transfer', 'SELECT')::text")).as("public")
                .isEqualTo("false");
        // ipd.admission: bed_id is newly updatable; the identity and billing columns are not
        for (String column : new String[] {"bed_id", "status", "discharged_at", "version"}) {
            assertThat(ownerText("select has_column_privilege('sahaj_app', 'ipd.admission', '" + column + "', 'UPDATE')::text"))
                    .as("update admission." + column).isEqualTo("true");
        }
        for (String column : new String[] {"id", "tenant_id", "patient_id", "ip_number", "admitted_at", "payer", "reason",
                "doctor_membership_id", "admission_type", "admitted_by"}) {
            assertThat(ownerText("select has_column_privilege('sahaj_app', 'ipd.admission', '" + column + "', 'UPDATE')::text"))
                    .as("update admission." + column).isEqualTo("false");
        }
    }

    // ---------------- check constraints ----------------

    @Test
    void theChecksRefuseBadDaysCategoriesRulesReasonsAndTheSameBedTwice() throws Exception {
        Moved m = moved();
        Clinic c = m.clinic();
        String cols = COLUMNS + " values (?::uuid, ?, ?, ";
        String ins = "insert into ipd.bed_transfer " + cols;
        Object[] ids = {m.admissionId(), m.newBed(), m.spareBed(), c.ownerUserId()};
        assertThat(deniedMessage(c, ins + "'gen', 'gen', -1, 'same', null, ?)", ids))
                .contains("ck_bed_transfer_segment_days");
        assertThat(deniedMessage(c, ins + "'xyz', 'gen', 0, 'same', null, ?)", ids)).contains("ck_bed_transfer_from_category");
        assertThat(deniedMessage(c, ins + "'gen', 'xyz', 0, 'same', null, ?)", ids)).contains("ck_bed_transfer_to_category");
        assertThat(deniedMessage(c, ins + "'General', 'gen', 0, 'same', null, ?)", ids))
                .contains("ck_bed_transfer_from_category");
        assertThat(deniedMessage(c, ins + "'gen', 'gen', 0, 'later', null, ?)", ids)).contains("ck_bed_transfer_new_rate_from");
        assertThat(deniedMessage(c, ins + "'gen', 'gen', 0, 'Same', null, ?)", ids)).contains("ck_bed_transfer_new_rate_from");
        for (String reason : new String[] {"Fever", "isolation", "", "Isolation ", "Needs closer monitoring."}) {
            assertThat(deniedMessage(c, ins + "'gen', 'gen', 0, 'same', '" + reason + "', ?)", ids)).as("reason " + reason)
                    .contains("ck_bed_transfer_reason");
        }
        // from <> to
        assertThat(deniedMessage(c, "insert into ipd.bed_transfer " + COLUMNS
                + " values (?::uuid, ?, ?, 'gen', 'gen', 0, 'same', null, ?)", m.admissionId(), m.newBed(), m.newBed(),
                c.ownerUserId())).contains("ck_bed_transfer_different_beds");
        // not null columns
        assertThat(deniedMessage(c, ins + "'gen', 'gen', null, 'same', null, ?)", ids)).contains("segment_days");
        assertThat(deniedMessage(c, ins + "'gen', 'gen', 0, null, null, ?)", ids)).contains("new_rate_from");
        // each allowed reason and category value is accepted (rolled back)
        for (String reason : new String[] {"Needs closer monitoring", "Moving to a step-down bed",
                "Patient or family request", "Isolation"}) {
            db().inTenant(c.tenantId(), conn -> {
                DbAccess.execute(conn, ins + "'semi', 'icu', 3, 'next', '" + reason + "', ?)", m.admissionId(), m.newBed(),
                        m.spareBed(), c.ownerUserId());
                return null;
            });
        }
        assertThat(rows(c, "bed_transfer")).isOne();
    }

    @Test
    void theConfigColumnsRefuseValuesOutsideTheTwoRuleChoices() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "next");         // creates the row
        assertThat(deniedMessage(clinic, "update ipd.config set new_rate_from = 'later'"))
                .contains("ck_ipd_config_new_rate_from");
        assertThat(deniedMessage(clinic, "update ipd.config set new_rate_from = 'Same'"))
                .contains("ck_ipd_config_new_rate_from");
        assertThat(deniedMessage(clinic, "update ipd.config set transfer_upgrade_owner_only = null")).contains("null value");
        assertThat(values(clinic, "select transfer_upgrade_owner_only::text, new_rate_from from ipd.config"))
                .containsExactly("false", "next");
        // the database defaults for a row that was created before the rules existed
        String defaults = ownerText("select string_agg(column_name || '=' || column_default, ',' "
                + "order by column_name) from information_schema.columns where table_schema = 'ipd' and table_name = 'config' "
                + "and column_name in ('transfer_upgrade_owner_only', 'new_rate_from')");
        assertThat(defaults).contains("new_rate_from='same'").contains("transfer_upgrade_owner_only=true");
    }

    // ---------------- history keeps a bed ----------------

    @Test
    void aBedThatHasATransferHistoryCannotBeDeletedNotEvenWhenItIsEmptyAndTheAnswerIsA409NotA500() throws Exception {
        Moved m = moved();
        Clinic c = m.clinic();
        ok(HttpMethod.POST, BEDS + "/" + m.oldBed() + "/ready", c.owner(), null, 200);   // empty and Available
        JsonNode p = problem(call(HttpMethod.DELETE, BEDS + "/" + m.oldBed(), c.owner(), null), 409);
        assertThat(p.get("status").asInt()).isEqualTo(409);
        assertThat(count(c, "select count(*) from ipd.bed where id = ?", m.oldBed())).isOne();
        assertThat(deniedMessage(c, "delete from ipd.bed where id = ?", m.oldBed())).contains("violates foreign key");
        // a bed that never had a patient can still be removed
        ok(HttpMethod.DELETE, BEDS + "/" + m.spareBed(), c.owner(), null, 200);
        assertThat(count(c, "select count(*) from ipd.bed where id = ?", m.spareBed())).isZero();
    }
}
