package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-27 the four new tables ({@code ipd.discharge_signoff}, {@code ipd.discharge}, {@code ipd.final_bill},
 * {@code ipd.final_bill_line}) and the {@code opd.follow_up} source columns, checked by SQL on the real
 * {@code sahaj_test} database: row level security, composite foreign keys across clinics, append-only for
 * {@code sahaj_app} and for the table owner, insertable columns, check and unique constraints. Test plan:
 * docs/test-plans/SAH-27.md.
 */
@OnTestDatabase
class DischargeTableIT extends DischargeSupport {

    private static final List<String> TABLES = List.of("discharge_signoff", "discharge", "final_bill", "final_bill_line");

    /** One clinic with an admitted patient who signed (live) and a discharged one (done). */
    private record Setup(Clinic clinic, String live, String liveSignoff, String done, String doneSignoff, String doneBill) {
    }

    private Setup setup() throws Exception {
        Clinic clinic = hospital();
        String live = quickAdmit(clinic).get("id").asText();
        signNormal(clinic, live);
        String done = quickAdmit(clinic).get("id").asText();
        signAndDischarge(clinic, done);
        return new Setup(clinic, live, values(clinic, "select id::text from ipd.discharge_signoff where admission_id = ?::uuid", live).get(0),
                done, values(clinic, "select id::text from ipd.discharge_signoff where admission_id = ?::uuid", done).get(0),
                values(clinic, "select id::text from ipd.final_bill where admission_id = ?::uuid", done).get(0));
    }

    private record Step(String sql, Object... params) {
    }

    /** Runs the statements in one rolled-back tenant transaction; the message of the first one that fails, or null. */
    private String firstError(Clinic clinic, Step... steps) throws SQLException {
        try {
            db().inTenant(clinic.tenantId(), c -> {
                for (Step s : steps) {
                    DbAccess.execute(c, s.sql(), s.params());
                }
                return null;
            });
        } catch (SQLException e) {
            return e.getMessage();
        }
        return null;
    }

    private String denied(Clinic clinic, Step... steps) throws SQLException {
        String message = firstError(clinic, steps);
        assertThat(message).as("the statements were accepted").isNotNull();
        return message;
    }

    private void accepted(Clinic clinic, Step... steps) throws SQLException {
        assertThat(firstError(clinic, steps)).as("a positive control must be accepted").isNull();
    }

    // ---------------- statements ----------------

    private static Step signoff(String admission, String type, String template, UUID by) {
        return new Step("insert into ipd.discharge_signoff (admission_id, discharge_type, template_key, signed_by) "
                + "values (?::uuid, " + type + ", " + template + ", ?)", admission, by);
    }

    private static Step dischargeRow(String admission, String signoffId, String type, String template, String days, String due,
                                     UUID by) {
        return new Step("insert into ipd.discharge (admission_id, signoff_id, discharge_type, template_key, signed_by, signed_at, "
                + "followup_days, followup_due, completed_by) values (?::uuid, ?::uuid, " + type + ", " + template + ", ?, now(), "
                + days + ", " + due + ", ?)", admission, signoffId, by, by);
    }

    private static Step bill(String admission, String total, String deposits, String balance, String mode, String days, String cut,
                             String category, String multiplier, UUID by) {
        return new Step("insert into ipd.final_bill (admission_id, total_paise, deposits_paise, balance_paise, settle_mode, "
                + "created_by, bill_days, bed_day_cut, category_key, multiplier) values (?::uuid, " + total + ", " + deposits + ", "
                + balance + ", " + mode + ", ?, " + days + ", " + cut + ", " + category + ", " + multiplier + ")", admission, by);
    }

    private static Step goodBill(String admission, UUID by) {
        return bill(admission, "160000", "1000000", "-840000", "'UPI'", "1", "'midnight'", "'gen'", "1.00", by);
    }

    private static Step line(String admission, String lineNo, String label, String qty, String unit, String amount) {
        return new Step("insert into ipd.final_bill_line (final_bill_id, line_no, label, qty, unit_paise, amount_paise, segment) "
                + "select id, " + lineNo + ", " + label + ", " + qty + ", " + unit + ", " + amount
                + ", false from ipd.final_bill where admission_id = ?::uuid", admission);
    }

    // ---------------- row level security and composite foreign keys ----------------

    @Test
    void rowsAreInvisibleWithoutATenantAndToAnotherClinicAndCannotPointAtAnotherClinicsAdmissionSignoffOrBill() throws Exception {
        Setup a = setup();
        Setup b = setup();
        for (String table : TABLES) {
            long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd." + table));
            assertThat(unbound).as(table + " with no tenant bound").isZero();
            assertThat(rows(a.clinic(), table)).as(table + " of A").isPositive();
            assertThat(count(b.clinic(), "select count(*) from ipd." + table + " where tenant_id = ?", a.clinic().tenantId()))
                    .as(table + " of A seen by B").isZero();
        }
        assertThat(count(b.clinic(), "select count(*) from ipd.final_bill where admission_id = ?::uuid", a.done())).isZero();
        UUID byB = b.clinic().ownerUserId();

        // positive controls in B (rolled back)
        accepted(b.clinic(), signoff(b.live(), "'lama'", "'general'", byB));
        accepted(b.clinic(), goodBill(b.live(), byB));
        accepted(b.clinic(), dischargeRow(b.live(), b.liveSignoff(), "'normal'", "'general'", "7", "'2030-01-01'::date", byB));
        // B's context with A's admission, A's sign-off or A's bill: no such (tenant, id) pair
        assertThat(denied(b.clinic(), signoff(a.live(), "'lama'", "'general'", byB))).contains("fk_discharge_signoff_admission");
        assertThat(denied(b.clinic(), goodBill(a.live(), byB))).contains("fk_final_bill_admission");
        assertThat(denied(b.clinic(), dischargeRow(a.live(), a.liveSignoff(), "'normal'", "'general'", "7", "'2030-01-01'::date", byB)))
                .contains("fk_discharge_admission");
        assertThat(denied(b.clinic(), dischargeRow(b.live(), a.liveSignoff(), "'normal'", "'general'", "7", "'2030-01-01'::date", byB)))
                .contains("fk_discharge_signoff");
        assertThat(denied(b.clinic(), goodBill(b.live(), byB), new Step("insert into ipd.final_bill_line "
                + "(final_bill_id, line_no, label, qty, unit_paise, amount_paise) values (?::uuid, 1, 'x', 1, 1, 1)", a.doneBill())))
                .contains("fk_final_bill_line_bill");
        // no tenant bound: nothing can be inserted
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, "insert into ipd.discharge_signoff (admission_id, discharge_type, template_key, signed_by) "
                    + "values (?::uuid, 'normal', 'general', ?)", b.live(), byB);
            return null;
        })).isInstanceOf(SQLException.class);
        // tenant_id is not an insertable column, so a foreign tenant id is refused before row level security
        for (String table : TABLES) {
            assertThat(denied(b.clinic(), new Step("insert into ipd." + table + " (tenant_id) values (?)", a.clinic().tenantId())))
                    .as(table).contains("permission denied");
        }
        assertThat(rows(a.clinic(), "final_bill")).isOne();
        assertThat(rows(b.clinic(), "final_bill")).isOne();
    }

    // ---------------- append-only ----------------

    @Test
    void sahajAppCannotUpdateDeleteOrTruncateAnyOfTheFourTablesAndCannotWriteIdTenantOrTheDefaultedTimes() throws Exception {
        Setup s = setup();
        Clinic c = s.clinic();
        String[][] updates = {{"discharge_signoff", "discharge_type = 'death'"}, {"discharge", "followup_days = 9"},
                {"final_bill", "total_paise = 1"}, {"final_bill_line", "label = 'x'"}};
        for (String[] u : updates) {
            assertThat(denied(c, new Step("update ipd." + u[0] + " set " + u[1]))).as("update " + u[0]).contains("permission denied");
            assertThat(denied(c, new Step("delete from ipd." + u[0]))).as("delete " + u[0]).contains("permission denied");
            assertThat(denied(c, new Step("truncate ipd." + u[0]))).as("truncate " + u[0]).contains("permission denied");
        }
        long bills = rows(c, "final_bill");
        long lines = rows(c, "final_bill_line");
        assertThat(bills).isOne();
        assertThat(lines).isEqualTo(3);

        // id and the defaulted times keep their defaults: they are not in the insert grants
        UUID by = c.ownerUserId();
        assertThat(denied(c, new Step("insert into ipd.discharge_signoff (id, admission_id, discharge_type, template_key, signed_by) "
                + "values (?, ?::uuid, 'lama', 'general', ?)", UUID.randomUUID(), s.live(), by))).contains("permission denied");
        assertThat(denied(c, new Step("insert into ipd.discharge_signoff (admission_id, discharge_type, template_key, signed_by, "
                + "signed_at) values (?::uuid, 'lama', 'general', ?, now() - interval '9 days')", s.live(), by)))
                .contains("permission denied");
        assertThat(denied(c, new Step("insert into ipd.final_bill (admission_id, total_paise, deposits_paise, balance_paise, "
                + "settle_mode, created_by, bill_days, bed_day_cut, category_key, multiplier, created_at) values (?::uuid, 0, 0, 0, "
                + "null, ?, 1, 'midnight', 'gen', 1, now() - interval '9 days')", s.live(), by))).contains("permission denied");
        assertThat(denied(c, new Step("insert into ipd.final_bill (id, admission_id, total_paise, deposits_paise, balance_paise, "
                + "settle_mode, created_by, bill_days, bed_day_cut, category_key, multiplier) values (?, ?::uuid, 0, 0, 0, null, ?, "
                + "1, 'midnight', 'gen', 1)", UUID.randomUUID(), s.live(), by))).contains("permission denied");
        assertThat(denied(c, new Step("insert into ipd.discharge (admission_id, signoff_id, discharge_type, template_key, signed_by, "
                + "signed_at, followup_days, followup_due, completed_by, completed_at) values (?::uuid, ?::uuid, 'normal', 'general', "
                + "?, now(), 7, current_date, ?, now() - interval '9 days')", s.live(), s.liveSignoff(), by, by)))
                .contains("permission denied");
        assertThat(denied(c, new Step("insert into ipd.final_bill_line (id, final_bill_id, line_no, label, qty, unit_paise, "
                + "amount_paise) values (?, ?::uuid, 9, 'x', 1, 1, 1)", UUID.randomUUID(), s.doneBill()))).contains("permission denied");
        // positive controls: the permitted columns work (rolled back); signed_at on the discharge is a permitted copy
        accepted(c, signoff(s.live(), "'lama'", "'general'", by));
        accepted(c, dischargeRow(s.live(), s.liveSignoff(), "'normal'", "'general'", "7", "'2030-01-01'::date", by));
        accepted(c, new Step("insert into ipd.final_bill_line (final_bill_id, line_no, label, qty, unit_paise, amount_paise, segment) "
                + "values (?::uuid, 9, 'x', 1, 1, 1, false)", s.doneBill()));
        assertThat(rows(c, "final_bill")).isEqualTo(bills);
        assertThat(rows(c, "final_bill_line")).isEqualTo(lines);
    }

    @Test
    void theTableOwnerIsStoppedByTheTriggersAndAllFourTablesStayForcedWithOnlySelectAndInsertPolicies() throws Exception {
        Setup s = setup();
        for (String table : TABLES) {
            assertThatThrownBy(() -> db().asMigrator(c -> {
                DbAccess.execute(c, "truncate ipd." + table);
                return null;
            })).as("truncate " + table).isInstanceOf(SQLException.class);
        }
        String[][] mutations = {{"discharge_signoff", "update ipd.discharge_signoff set discharge_type = 'death'"},
                {"discharge", "update ipd.discharge set followup_days = 9"}, {"final_bill", "update ipd.final_bill set total_paise = 1"},
                {"final_bill_line", "update ipd.final_bill_line set label = 'x'"},
                {"discharge_signoff", "delete from ipd.discharge_signoff"}, {"discharge", "delete from ipd.discharge"},
                {"final_bill", "delete from ipd.final_bill"}, {"final_bill_line", "delete from ipd.final_bill_line"}};
        for (String[] m : mutations) {
            assertThatThrownBy(() -> db().asMigrator(c -> {
                DbAccess.execute(c, "alter table ipd." + m[0] + " no force row level security");
                DbAccess.execute(c, m[1]);
                return null;
            })).as(m[1]).isInstanceOf(SQLException.class);
        }
        assertThat(rows(s.clinic(), "final_bill")).isOne();
        assertThat(rows(s.clinic(), "final_bill_line")).isEqualTo(3);
        assertThat(rows(s.clinic(), "discharge")).isOne();

        for (String table : TABLES) {
            String flags = db().asMigrator(c -> DbAccess.text(c, "select relrowsecurity::text || ',' || relforcerowsecurity::text "
                    + "from pg_class where oid = 'ipd." + table + "'::regclass"));
            assertThat(flags).as(table).isEqualTo("true,true");
            String policies = db().asMigrator(c -> DbAccess.text(c, "select string_agg(cmd, ',' order by cmd) from pg_policies "
                    + "where schemaname = 'ipd' and tablename = '" + table + "'"));
            assertThat(policies).as(table + " policies").isEqualTo("INSERT,SELECT");
            long triggers = db().asMigrator(c -> DbAccess.count(c, "select count(*) from pg_trigger "
                    + "where tgrelid = 'ipd." + table + "'::regclass and not tgisinternal"));
            assertThat(triggers).as(table + " triggers").isEqualTo(2);
        }
    }

    @Test
    void theGrantsAreExactlySelectAndTheListedInsertColumnsAndNoUpdateAnywhere() throws Exception {
        Setup s = setup();
        String[][] insertable = {
                {"discharge_signoff", "admission_id,discharge_type,template_key,signed_by"},
                {"discharge", "admission_id,signoff_id,discharge_type,template_key,signed_by,signed_at,followup_days,followup_due,completed_by"},
                {"final_bill", "admission_id,total_paise,deposits_paise,balance_paise,settle_mode,created_by,bill_days,bed_day_cut,category_key,multiplier"},
                {"final_bill_line", "final_bill_id,line_no,label,qty,unit_paise,amount_paise,segment"}};
        for (String[] t : insertable) {
            List<String> allowed = List.of(t[1].split(","));
            List<String> columns = db().asMigrator(c -> {
                List<String> out = new java.util.ArrayList<>();
                try (var ps = c.prepareStatement("select column_name from information_schema.columns where table_schema = 'ipd' "
                        + "and table_name = ? order by ordinal_position")) {
                    ps.setString(1, t[0]);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(rs.getString(1));
                        }
                    }
                }
                return out;
            });
            assertThat(columns).as(t[0] + " columns").containsAll(allowed).contains("id", "tenant_id");
            for (String column : columns) {
                String canInsert = db().asMigrator(c -> DbAccess.text(c, "select has_column_privilege('sahaj_app', 'ipd." + t[0]
                        + "', '" + column + "', 'INSERT')::text"));
                assertThat(canInsert).as("insert " + t[0] + "." + column).isEqualTo(String.valueOf(allowed.contains(column)));
                String canUpdate = db().asMigrator(c -> DbAccess.text(c, "select has_column_privilege('sahaj_app', 'ipd." + t[0]
                        + "', '" + column + "', 'UPDATE')::text"));
                assertThat(canUpdate).as("update " + t[0] + "." + column).isEqualTo("false");
            }
            for (String privilege : new String[] {"DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "UPDATE"}) {
                assertThat(ownerText("select has_table_privilege('sahaj_app', 'ipd." + t[0] + "', '"
                        + privilege + "')::text")).as(t[0] + " " + privilege).isEqualTo("false");
            }
            assertThat(ownerText("select has_table_privilege('sahaj_app', 'ipd." + t[0]
                    + "', 'SELECT')::text")).isEqualTo("true");
            assertThat(ownerText("select has_table_privilege('public', 'ipd." + t[0]
                    + "', 'SELECT')::text")).as("public " + t[0]).isEqualTo("false");
        }
        // the existing tables only gained what they needed
        for (String column : new String[] {"status", "discharged_at", "version"}) {
            assertThat(ownerText("select has_column_privilege('sahaj_app', 'ipd.admission', '" + column
                    + "', 'UPDATE')::text")).as("admission." + column).isEqualTo("true");
        }
        assertThat(rows(s.clinic(), "discharge")).isOne();
    }

    // ---------------- check and unique constraints ----------------

    @Test
    void theChecksRefuseBadTypesTemplatesModesAmountsBalancesAndLines() throws Exception {
        Setup s = setup();
        Clinic c = s.clinic();
        UUID by = c.ownerUserId();

        // sign-off
        for (String type : new String[] {"'Normal'", "'burial'", "''", "'LAMA'"}) {
            assertThat(denied(c, signoff(s.live(), type, "'general'", by))).as(type).contains("ck_discharge_signoff_type");
        }
        for (String template : new String[] {"'General'", "'xyz'", "'general medicine'", "''"}) {
            assertThat(denied(c, signoff(s.live(), "'normal'", template, by))).as(template).contains("ck_discharge_signoff_template");
        }
        assertThat(denied(c, signoff(s.live(), "null", "'general'", by))).contains("discharge_type");
        assertThat(denied(c, signoff(s.live(), "'normal'", "null", by))).contains("template_key");
        for (String type : new String[] {"'normal'", "'lama'", "'referred'", "'death'"}) {
            for (String template : new String[] {"'general'", "'surgical'", "'maternity'", "'daycare'"}) {
                accepted(c, signoff(s.live(), type, template, by));
            }
        }

        // discharge record
        String sg = s.liveSignoff();
        assertThat(denied(c, dischargeRow(s.live(), sg, "'Normal'", "'general'", "7", "'2030-01-01'::date", by))).contains("ck_discharge_type");
        assertThat(denied(c, dischargeRow(s.live(), sg, "'normal'", "'xyz'", "7", "'2030-01-01'::date", by))).contains("ck_discharge_template");
        assertThat(denied(c, dischargeRow(s.live(), sg, "'normal'", "'general'", "0", "'2030-01-01'::date", by))).contains("ck_discharge_followup_days");
        assertThat(denied(c, dischargeRow(s.live(), sg, "'normal'", "'general'", "31", "'2030-01-01'::date", by))).contains("ck_discharge_followup_days");
        assertThat(denied(c, dischargeRow(s.live(), sg, "'death'", "'general'", "7", "'2030-01-01'::date", by))).as("death with a due date")
                .contains("ck_discharge_followup_due");
        assertThat(denied(c, dischargeRow(s.live(), sg, "'normal'", "'general'", "7", "null::date", by))).as("no death, no due date")
                .contains("ck_discharge_followup_due");
        accepted(c, dischargeRow(s.live(), sg, "'death'", "'general'", "7", "null::date", by));
        accepted(c, dischargeRow(s.live(), sg, "'lama'", "'surgical'", "30", "'2030-01-01'::date", by));

        // final bill
        assertThat(denied(c, bill(s.live(), "-1", "0", "-1", "'UPI'", "1", "'midnight'", "'gen'", "1", by))).contains("ck_final_bill_total");
        assertThat(denied(c, bill(s.live(), "0", "-1", "1", "'UPI'", "1", "'midnight'", "'gen'", "1", by))).contains("ck_final_bill_deposits");
        assertThat(denied(c, bill(s.live(), "100", "0", "99", "'UPI'", "1", "'midnight'", "'gen'", "1", by))).contains("ck_final_bill_balance");
        assertThat(denied(c, bill(s.live(), "100", "100", "0", "'UPI'", "1", "'midnight'", "'gen'", "1", by))).as("settled with a mode")
                .contains("ck_final_bill_settle");
        assertThat(denied(c, bill(s.live(), "100", "0", "100", "null", "1", "'midnight'", "'gen'", "1", by))).as("collected with no mode")
                .contains("ck_final_bill_settle");
        for (String mode : new String[] {"'upi'", "'Cheque'", "''"}) {
            assertThat(denied(c, bill(s.live(), "100", "0", "100", mode, "1", "'midnight'", "'gen'", "1", by))).as(mode)
                    .contains("ck_final_bill_settle_mode");
        }
        assertThat(denied(c, bill(s.live(), "100", "0", "100", "'UPI'", "0", "'midnight'", "'gen'", "1", by))).contains("ck_final_bill_bill_days");
        assertThat(denied(c, bill(s.live(), "100", "0", "100", "'UPI'", "1", "'24H'", "'gen'", "1", by))).contains("ck_final_bill_bed_day_cut");
        assertThat(denied(c, bill(s.live(), "100", "0", "100", "'UPI'", "1", "'midnight'", "'General'", "1", by))).contains("ck_final_bill_category");
        assertThat(denied(c, bill(s.live(), "100", "0", "100", "'UPI'", "1", "'midnight'", "'gen'", "0.49", by))).contains("ck_final_bill_multiplier");
        assertThat(denied(c, bill(s.live(), "100", "0", "100", "'UPI'", "1", "'midnight'", "'gen'", "5.01", by))).contains("ck_final_bill_multiplier");
        accepted(c, bill(s.live(), "100", "100", "0", "null", "1", "'24h'", "'icu'", "5", by));
        accepted(c, bill(s.live(), "0", "100", "-100", "'Card'", "9", "'midnight'", "'semi'", "0.5", by));

        // lines
        Step good = goodBill(s.live(), by);
        assertThat(denied(c, good, line(s.live(), "0", "'Bed'", "1", "1", "1"))).contains("ck_final_bill_line_no");
        assertThat(denied(c, good, line(s.live(), "1", "''", "1", "1", "1"))).contains("ck_final_bill_line_label");
        assertThat(denied(c, good, line(s.live(), "1", "repeat('x', 201)", "1", "1", "1"))).contains("ck_final_bill_line_label");
        assertThat(denied(c, good, line(s.live(), "1", "'Bed'", "-1", "80000", "-80000"))).contains("ck_final_bill_line_qty");
        assertThat(denied(c, good, line(s.live(), "1", "'Bed'", "1", "-1", "-1"))).contains("ck_final_bill_line_unit");
        assertThat(denied(c, good, line(s.live(), "1", "'Bed'", "2", "100", "201"))).contains("ck_final_bill_line_amount");
        assertThat(denied(c, good, line(s.live(), "1", "'Bed'", "1", "1", "1"), line(s.live(), "1", "'Nursing'", "1", "1", "1")))
                .contains("uq_final_bill_line_no");
        accepted(c, good, line(s.live(), "1", "repeat('x', 200)", "0", "80000", "0"), line(s.live(), "2", "'Bed'", "3", "80000", "240000"));
    }

    @Test
    void aSecondBillOrDischargeForTheSameAdmissionIsRefusedByTheDatabaseAndASignoffOfAnotherAdmissionCannotBeUsed() throws Exception {
        Setup s = setup();
        Clinic c = s.clinic();
        UUID by = c.ownerUserId();
        assertThat(denied(c, goodBill(s.done(), by))).contains("uq_final_bill_tenant_admission");
        assertThat(denied(c, dischargeRow(s.done(), s.doneSignoff(), "'normal'", "'general'", "7", "'2030-01-01'::date", by)))
                .contains("uq_discharge_tenant_admission");
        // a sign-off belongs to its admission: the discharge of 'live' cannot use the sign-off of 'done'
        assertThat(denied(c, dischargeRow(s.live(), s.doneSignoff(), "'normal'", "'general'", "7", "'2030-01-01'::date", by)))
                .contains("fk_discharge_signoff");
        assertThat(denied(c, dischargeRow(s.live(), UUID.randomUUID().toString(), "'normal'", "'general'", "7", "'2030-01-01'::date", by)))
                .contains("fk_discharge_signoff");
        // a line numbered twice in the frozen bill
        assertThat(denied(c, new Step("insert into ipd.final_bill_line (final_bill_id, line_no, label, qty, unit_paise, amount_paise) "
                + "values (?::uuid, 1, 'x', 1, 1, 1)", s.doneBill()))).contains("uq_final_bill_line_no");
        assertThat(rows(c, "final_bill")).isOne();
        assertThat(rows(c, "discharge")).isOne();
    }

    // ---------------- the OPD follow-up table ----------------

    @Test
    void aFollowUpHasExactlyOneSourceAndOneRowPerAdmissionAndTheVisitMayBeNull() throws Exception {
        Setup s = setup();
        Clinic c = s.clinic();
        String patient = patientOf(c, s.live());
        UUID doctor = c.doctorMembershipId();
        UUID by = c.ownerUserId();
        String insertAdmission = "insert into opd.follow_up (id, patient_id, doctor_membership_id, source_admission_id, due_date, "
                + "reason, created_by) values (?, ?::uuid, ?, ?, current_date, 'r', ?)";
        String insertBoth = "insert into opd.follow_up (id, patient_id, doctor_membership_id, source_visit_id, source_admission_id, "
                + "due_date, reason, created_by) values (?, ?::uuid, ?, ?, ?, current_date, 'r', ?)";
        String insertNone = "insert into opd.follow_up (id, patient_id, doctor_membership_id, due_date, reason, created_by) "
                + "values (?, ?::uuid, ?, current_date, 'r', ?)";
        UUID source = UUID.randomUUID();
        accepted(c, new Step(insertAdmission, UUID.randomUUID(), patient, doctor, source, by));
        assertThat(denied(c, new Step(insertNone, UUID.randomUUID(), patient, doctor, by))).contains("ck_follow_up_one_source");
        assertThat(denied(c, new Step(insertBoth, UUID.randomUUID(), patient, doctor, UUID.randomUUID(), UUID.randomUUID(), by)))
                .contains("ck_follow_up_one_source");
        // one follow-up per admission
        assertThat(denied(c, new Step(insertAdmission, UUID.randomUUID(), patient, doctor, source, by),
                new Step(insertAdmission, UUID.randomUUID(), patient, doctor, source, by))).contains("uq_follow_up_source_admission");
        // the discharge made by the API already holds the key of its admission
        assertThat(denied(c, new Step(insertAdmission, UUID.randomUUID(), patient, doctor, UUID.fromString(s.done()), by)))
                .contains("uq_follow_up_source_admission");
        // many rows with no visit are fine (nulls are distinct)
        accepted(c, new Step(insertAdmission, UUID.randomUUID(), patient, doctor, UUID.randomUUID(), by),
                new Step(insertAdmission, UUID.randomUUID(), patient, doctor, UUID.randomUUID(), by));
        // row level security and the missing delete grant
        assertThat(denied(c, new Step("delete from opd.follow_up"))).contains("permission denied");
        long unbound = db().asApp(x -> DbAccess.count(x, "select count(*) from opd.follow_up"));
        assertThat(unbound).isZero();
    }
}
