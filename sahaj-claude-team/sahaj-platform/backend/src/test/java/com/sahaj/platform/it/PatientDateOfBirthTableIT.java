package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The nine-item QA list of {@code docs/database/SAH-73.md} for {@code patient.patient.date_of_birth} by plain SQL on the
 * real {@code sahaj_test} database, as {@code sahaj_app} (clinic bound) and as the table owner: the column and its type,
 * a legacy insert, the 1900-01-01 floor, the null round trip, the unchanged age check, row level security, the grants,
 * the column comment, and (case 9) the service rules in {@link PatientDateOfBirthIT}. Test plan: docs/test-plans/SAH-73.md.
 */
@OnTestDatabase
class PatientDateOfBirthTableIT extends EmrSupport {

    private static final String INSERT = "insert into patient.patient (id, name, age, sex, mobile, date_of_birth, created_by) "
            + "values (?::uuid, ?, ?, 'M', ?, ?::date, ?::uuid)";
    private static final String INSERT_NO_DATE = "insert into patient.patient (id, name, age, sex, mobile, created_by) "
            + "values (?::uuid, ?, ?, 'M', ?, ?::uuid)";

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    // ---------------- 1 the column ----------------

    @Test
    void theColumnIsANullableDateWithNoDefaultAndTheMigrationIsApplied() throws Exception {
        List<List<String>> column = globalRows("select data_type, is_nullable, coalesce(column_default, '<none>') "
                + "from information_schema.columns where table_schema = 'patient' and table_name = 'patient' and column_name = 'date_of_birth'");
        assertThat(column).containsExactly(List.of("date", "YES", "<none>"));
        long applied = db().asMigrator(c -> DbAccess.count(c,
                "select count(*) from platform.flyway_schema_history where script like '%patient_date_of_birth%' and success"));
        assertThat(applied).isEqualTo(1);
        // age is unchanged: smallint, not null
        assertThat(globalRows("select data_type, is_nullable from information_schema.columns where table_schema = 'patient' "
                + "and table_name = 'patient' and column_name = 'age'")).containsExactly(List.of("smallint", "NO"));
    }

    // ---------------- 2 legacy insert ----------------

    @Test
    void aLegacyInsertWithoutTheColumnSucceedsAndReadsNull() throws Exception {
        Clinic clinic = newClinic();
        String id = newId();
        commit(clinic.tenantId(), INSERT_NO_DATE, id, "Legacy Row", 40, uniqueMobile(), clinic.ownerUserId().toString());
        assertThat(row(clinic, "select (date_of_birth is null)::text, age::text from patient.patient where id = ?::uuid", id))
                .containsExactly("true", "40");
    }

    // ---------------- 3 the floor ----------------

    @Test
    void the1900FloorAcceptsTheFirstOfJanuary1900AndRefusesTheDayBefore() throws Exception {
        Clinic clinic = newClinic();
        db().inTenant(clinic.tenantId(), c -> {
            expectOk(c, INSERT, newId(), "Floor Ok", 120, uniqueMobile(), "1900-01-01", clinic.ownerUserId().toString());
            SqlError refused = expectError(c, INSERT, newId(), "Floor Refused", 120, uniqueMobile(), "1899-12-31",
                    clinic.ownerUserId().toString());
            assertThat(refused.state()).isEqualTo("23514");
            assertThat(refused.message()).contains("ck_patient_dob_floor");
            // an ordinary and a very recent date are accepted
            expectOk(c, INSERT, newId(), "Recent", 0, uniqueMobile(), java.time.LocalDate.now().toString(), clinic.ownerUserId().toString());
            return null;
        });
    }

    // ---------------- 4 null round trip ----------------

    @Test
    void anInsertWithNullAndAnUpdateFromADateBackToNullBothSucceed() throws Exception {
        Clinic clinic = newClinic();
        String withNull = newId();
        String withDate = newId();
        commit(clinic.tenantId(), "insert into patient.patient (id, name, age, sex, mobile, date_of_birth, created_by) "
                + "values (?::uuid, ?, ?, 'F', ?, null, ?::uuid)", withNull, "Null Insert", 30, uniqueMobile(), clinic.ownerUserId().toString());
        assertThat(row(clinic, "select (date_of_birth is null)::text from patient.patient where id = ?::uuid", withNull)).containsExactly("true");
        commit(clinic.tenantId(), INSERT, withDate, "Date Insert", 5, uniqueMobile(), "2021-04-05", clinic.ownerUserId().toString());
        assertThat(row(clinic, "select date_of_birth::text from patient.patient where id = ?::uuid", withDate)).containsExactly("2021-04-05");
        commit(clinic.tenantId(), "update patient.patient set date_of_birth = null where id = ?::uuid", withDate);
        assertThat(row(clinic, "select (date_of_birth is null)::text from patient.patient where id = ?::uuid", withDate)).containsExactly("true");
        commit(clinic.tenantId(), "update patient.patient set date_of_birth = '2020-02-29' where id = ?::uuid", withDate);
        assertThat(row(clinic, "select date_of_birth::text from patient.patient where id = ?::uuid", withDate)).containsExactly("2020-02-29");
    }

    // ---------------- 5 the age check is unchanged ----------------

    @Test
    void theAgeCheckStillRefusesAnAgeAbove120AndBelowZeroWithAndWithoutADate() throws Exception {
        Clinic clinic = newClinic();
        db().inTenant(clinic.tenantId(), c -> {
            String user = clinic.ownerUserId().toString();
            for (int bad : new int[] {121, -1}) {
                SqlError noDate = expectError(c, INSERT_NO_DATE, newId(), "Age Bad", bad, uniqueMobile(), user);
                assertThat(noDate.state()).as("no date, age " + bad).isEqualTo("23514");
                assertThat(noDate.message()).contains("ck_patient_age");
                SqlError withDate = expectError(c, INSERT, newId(), "Age Bad Dated", bad, uniqueMobile(), "2015-06-01", user);
                assertThat(withDate.state()).as("dated, age " + bad).isEqualTo("23514");
                assertThat(withDate.message()).contains("ck_patient_age");
            }
            expectOk(c, INSERT, newId(), "Age Edge", 120, uniqueMobile(), "1910-01-01", user);
            expectOk(c, INSERT_NO_DATE, newId(), "Age Zero", 0, uniqueMobile(), user);
            return null;
        });
    }

    // ---------------- 6 row level security ----------------

    @Test
    void anotherClinicCannotSeeOrChangeTheDateAndNoClinicBoundSeesNothing() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        String id = newId();
        commit(a.tenantId(), INSERT, id, "Rls Row", 6, uniqueMobile(), "2020-05-05", a.ownerUserId().toString());
        assertThat(count(a, "select count(*) from patient.patient where id = ?::uuid and date_of_birth is not null", id)).isEqualTo(1);
        assertThat(count(b, "select count(*) from patient.patient where id = ?::uuid", id)).as("tenant B sees no row").isZero();
        assertThat(count(b, "select count(*) from patient.patient where date_of_birth = date '2020-05-05' and id = ?::uuid", id)).isZero();
        int changed = db().inTenant(b.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement("update patient.patient set date_of_birth = null where id = ?::uuid")) {
                ps.setString(1, id);
                return ps.executeUpdate();
            }
        });
        assertThat(changed).as("tenant B cannot update it").isZero();
        assertThat(row(a, "select date_of_birth::text from patient.patient where id = ?::uuid", id)).containsExactly("2020-05-05");
        long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from patient.patient where id = ?::uuid", id));
        assertThat(unbound).as("no clinic bound: zero rows").isZero();
        // tenant B cannot write a row for tenant A either
        db().inTenant(b.tenantId(), c -> {
            SqlError refused = expectError(c, "insert into patient.patient (id, tenant_id, name, age, sex, mobile, date_of_birth, created_by) "
                    + "values (?::uuid, ?::uuid, 'Cross Tenant', 5, 'M', ?, '2020-05-05', ?::uuid)", newId(), a.tenantId().toString(),
                    uniqueMobile(), b.ownerUserId().toString());
            assertThat(refused.state()).isEqualTo("42501");
            return null;
        });
    }

    // ---------------- 7 grants ----------------

    @Test
    void sahajAppMayInsertAndUpdateTheColumnAndHasNoDeleteOnThePatientTable() throws Exception {
        Clinic clinic = newClinic();
        List<List<String>> grants = globalRows("select has_table_privilege('sahaj_app', 'patient.patient', 'SELECT')::text, "
                + "has_table_privilege('sahaj_app', 'patient.patient', 'INSERT')::text, "
                + "has_table_privilege('sahaj_app', 'patient.patient', 'UPDATE')::text, "
                + "has_table_privilege('sahaj_app', 'patient.patient', 'DELETE')::text, "
                + "has_column_privilege('sahaj_app', 'patient.patient', 'date_of_birth', 'INSERT')::text, "
                + "has_column_privilege('sahaj_app', 'patient.patient', 'date_of_birth', 'UPDATE')::text");
        assertThat(grants).containsExactly(List.of("true", "true", "true", "false", "true", "true"));
        String id = newId();
        commit(clinic.tenantId(), INSERT, id, "Grant Row", 7, uniqueMobile(), "2019-01-01", clinic.ownerUserId().toString());
        commit(clinic.tenantId(), "update patient.patient set date_of_birth = '2019-01-02' where id = ?::uuid", id);
        db().inTenant(clinic.tenantId(), c -> {
            SqlError delete = expectError(c, "delete from patient.patient where id = ?::uuid", id);
            assertThat(delete.state()).isEqualTo("42501");
            return null;
        });
        assertThat(row(clinic, "select date_of_birth::text from patient.patient where id = ?::uuid", id)).containsExactly("2019-01-02");
    }

    // ---------------- 8 the comment ----------------

    @Test
    void theColumnCommentSaysNeverLog() throws Exception {
        List<List<String>> comment = globalRows("select lower(col_description('patient.patient'::regclass, a.attnum)) "
                + "from pg_attribute a where a.attrelid = 'patient.patient'::regclass and a.attname = 'date_of_birth'");
        assertThat(comment).hasSize(1);
        assertThat(comment.get(0).get(0)).contains("never log").contains("personal data");
    }

    // ---------------- 9 the stored age is only a snapshot (service rules are in PatientDateOfBirthIT) ----------------

    @Test
    void aStoredAgeThatDisagreesWithTheDateDoesNotViolateAnyConstraint() throws Exception {
        Clinic clinic = newClinic();
        String id = newId();
        // the database keeps only the fixed rules; "age equals the completed years" is the service's job (ADR-024 D4)
        commit(clinic.tenantId(), INSERT, id, "Snapshot Row", 99, uniqueMobile(), "2020-05-05", clinic.ownerUserId().toString());
        assertThat(row(clinic, "select age::text, date_of_birth::text from patient.patient where id = ?::uuid", id))
                .containsExactly("99", "2020-05-05");
    }
}
