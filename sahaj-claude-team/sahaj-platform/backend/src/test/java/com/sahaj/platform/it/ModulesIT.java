package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.infrastructure.security.TenantPrincipal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SAH-19 Plan and modules on the real {@code sahaj_test} database: lazy defaults, IPD request and trial,
 * expired trial, HRA switch, audit, plan mapping and row level security.
 */
@OnTestDatabase
class ModulesIT extends SweepSupport {

    static final String MODULES = "/api/v1/platform/modules";
    static final String REQUEST = MODULES + "/ipd-request";
    static final String TRIAL = MODULES + "/ipd-trial";
    static final String HRA = MODULES + "/hra";
    private static final String AUDIT = "/api/v1/platform/audit-events?limit=100";
    private static final String[] NON_OWNERS = {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};
    private static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};

    @Autowired
    private ModuleEntitlementPort port;

    private long count(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> DbAccess.count(c, sql, params));
    }

    private long rows(Clinic clinic) throws SQLException {
        return count(clinic, "select count(*) from platform.module_entitlement");
    }

    private long audits(Clinic clinic, String action) throws SQLException {
        return count(clinic, "select count(*) from platform.audit_event where action = ?", action);
    }

    private JsonNode auditEvent(Clinic clinic, String action) throws Exception {
        for (JsonNode e : ok(HttpMethod.GET, AUDIT, clinic.owner(), null, 200)) {
            if (action.equals(e.get("action").asText())) {
                return e;
            }
        }
        return null;
    }

    /** Committed statement as sahaj_app with the tenant bound. */
    private void commitAsTenant(UUID tenantId, String sql, Object... params) throws SQLException {
        try (Connection c = db().app()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, tenantId.toString());
                bind.execute();
            }
            DbAccess.execute(c, sql, params);
            c.commit();
        }
    }

    private void assertDenied(Clinic clinic, String sql) {
        assertThatThrownBy(() -> db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, sql);
            return null;
        })).isInstanceOf(SQLException.class);
    }

    private void bindPrincipal(Clinic clinic) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TenantPrincipal(clinic.ownerUserId(), clinic.tenantId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    // ---------------- GET ----------------

    @Test
    void freshTenantGetsDefaultsAndGetWritesNoRow() throws Exception {
        Clinic clinic = newClinic();
        JsonNode m = ok(HttpMethod.GET, MODULES, clinic.owner(), null, 200);
        assertThat(m.get("plan").asText()).isEqualTo("FREE");
        assertThat(m.get("ipd").get("state").asText()).isEqualTo("off");
        assertThat(m.get("ipd").get("requested").asBoolean()).isFalse();
        assertThat(m.get("ipd").get("requestedAt").isNull()).isTrue();
        assertThat(m.get("ipdTrialAvailable").asBoolean()).isTrue();
        assertThat(m.get("hra").get("on").asBoolean()).isFalse();
        int simple = m.get("menuCounts").get("simple").asInt();
        int advanced = m.get("menuCounts").get("advanced").asInt();
        assertThat(simple).isPositive();
        assertThat(advanced).isGreaterThanOrEqualTo(simple);
        ok(HttpMethod.GET, MODULES, clinic.owner(), null, 200);
        assertThat(rows(clinic)).isZero();
    }

    @Test
    void allEightRolesCanReadAndUnauthenticatedGets401() throws Exception {
        Clinic clinic = newClinic();
        for (String role : ALL_ROLES) {
            JsonNode m = ok(HttpMethod.GET, MODULES, clinic.as(role), null, 200);
            assertThat(m.get("menuCounts").get("simple").asInt()).as(role).isPositive();
        }
        mvc.perform(get(MODULES)).andExpect(status().isUnauthorized());
        assertThat(rows(clinic)).isZero();
    }

    // ---------------- IPD request ----------------

    @Test
    void ownerRequestsIpdSecondCallKeepsFirstTimeAndOneAuditEvent() throws Exception {
        Clinic clinic = newClinic();
        JsonNode first = ok(HttpMethod.POST, REQUEST, clinic.owner(), null, 200);
        assertThat(first.get("ipd").get("state").asText()).isEqualTo("off");
        assertThat(first.get("ipd").get("requested").asBoolean()).isTrue();
        assertThat(first.get("ipd").get("requestedAt").isNull()).isFalse();
        assertThat(rows(clinic)).isEqualTo(1);
        String stored1 = db().<String>inTenant(clinic.tenantId(),
                c -> DbAccess.text(c, "select ipd_requested_at::text from platform.module_entitlement"));
        long v1 = count(clinic, "select version from platform.module_entitlement");

        Thread.sleep(50);
        JsonNode second = ok(HttpMethod.POST, REQUEST, clinic.owner(), null, 200);
        assertThat(second.get("ipd").get("requestedAt").asText()).isEqualTo(first.get("ipd").get("requestedAt").asText());
        String stored2 = db().<String>inTenant(clinic.tenantId(),
                c -> DbAccess.text(c, "select ipd_requested_at::text from platform.module_entitlement"));
        assertThat(stored2).isEqualTo(stored1);
        assertThat(count(clinic, "select version from platform.module_entitlement")).isEqualTo(v1);
        assertThat(audits(clinic, "MODULE_IPD_REQUESTED")).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, MODULES, clinic.as("DOCTOR"), null, 200).get("ipd").get("requested").asBoolean()).isTrue();
    }

    @Test
    void requestIsConflictOnceIpdIsNotOff() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        JsonNode p = problem(call(HttpMethod.POST, REQUEST, clinic.owner(), null), 409);
        assertThat(p.get("type").asText()).endsWith("ipd-not-off");
    }

    @Test
    void nonOwnersCannotRequestIpd() throws Exception {
        Clinic clinic = newClinic();
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.POST, REQUEST, clinic.as(role), null), 403);
        }
        assertThat(rows(clinic)).isZero();
    }

    // ---------------- IPD trial ----------------

    @Test
    void ownerStartsTrialWithThirtyDaysAndAudit() throws Exception {
        Clinic clinic = newClinic();
        Instant before = Instant.now();
        JsonNode m = ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        assertThat(m.get("ipd").get("state").asText()).isEqualTo("trial");
        Instant ends = Instant.parse(m.get("ipd").get("trialEndsAt").asText());
        assertThat(Duration.between(before, ends))
                .isBetween(Duration.ofDays(30).minusMinutes(2), Duration.ofDays(30).plusMinutes(2));
        assertThat(m.get("ipdTrialAvailable").asBoolean()).isFalse();
        assertThat(ok(HttpMethod.GET, MODULES, clinic.as("NURSE"), null, 200).get("ipd").get("state").asText())
                .isEqualTo("trial");
        assertThat(rows(clinic)).isEqualTo(1);

        JsonNode e = auditEvent(clinic, "MODULE_IPD_TRIAL_STARTED");
        assertThat(e).as("MODULE_IPD_TRIAL_STARTED audit event").isNotNull();
        assertThat(e.get("before").get("ipdState").asText()).isEqualTo("off");
        assertThat(e.get("after").get("ipdState").asText()).isEqualTo("trial");
        assertThat(e.get("after").get("ipdTrialEndsAt").isNull()).isFalse();
        assertThat(audits(clinic, "MODULE_IPD_TRIAL_STARTED")).isEqualTo(1);
    }

    @Test
    void secondTrialIsConflict() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        problem(call(HttpMethod.POST, TRIAL, clinic.owner(), null), 409);
        assertThat(audits(clinic, "MODULE_IPD_TRIAL_STARTED")).isEqualTo(1);
    }

    @Test
    void trialKeepsAnEarlierRequestedAt() throws Exception {
        Clinic clinic = newClinic();
        JsonNode requested = ok(HttpMethod.POST, REQUEST, clinic.owner(), null, 200);
        JsonNode trial = ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        assertThat(trial.get("ipd").get("requestedAt").asText()).isEqualTo(requested.get("ipd").get("requestedAt").asText());
        assertThat(trial.get("ipd").get("requested").asBoolean()).isTrue();
        assertThat(rows(clinic)).isEqualTo(1);
    }

    @Test
    void nonOwnersCannotStartTrial() throws Exception {
        Clinic clinic = newClinic();
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.POST, TRIAL, clinic.as(role), null), 403);
        }
        assertThat(rows(clinic)).isZero();
    }

    // ---------------- Expired trial ----------------

    @Test
    void expiredTrialReadsAsReadonlyAndRequestAndTrialAreConflicts() throws Exception {
        Clinic clinic = newClinic();
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        commitAsTenant(clinic.tenantId(),
                "update platform.module_entitlement set ipd_trial_ends_at = now() - interval '1 day'");

        JsonNode m = ok(HttpMethod.GET, MODULES, clinic.owner(), null, 200);
        assertThat(m.get("ipd").get("state").asText()).isEqualTo("readonly");
        assertThat(m.get("ipdTrialAvailable").asBoolean()).isFalse();
        // Expiry is a read-time rule: the stored state is untouched.
        assertThat(db().<String>inTenant(clinic.tenantId(),
                c -> DbAccess.text(c, "select ipd_state from platform.module_entitlement"))).isEqualTo("trial");

        problem(call(HttpMethod.POST, REQUEST, clinic.owner(), null), 409);
        problem(call(HttpMethod.POST, TRIAL, clinic.owner(), null), 409);

        bindPrincipal(clinic);
        try {
            assertThat(port.forTenant(clinic.tenantId()).ipdState()).isEqualTo("readonly");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void portReportsDefaultsAndThenHra() throws Exception {
        Clinic clinic = newClinic();
        bindPrincipal(clinic);
        try {
            var d = port.forTenant(clinic.tenantId());
            assertThat(d.ipdState()).isEqualTo("off");
            assertThat(d.hraOn()).isFalse();
            assertThat(d.bedLimit()).isEqualTo(25);
            SecurityContextHolder.clearContext();
            ok(HttpMethod.PUT, HRA, clinic.owner(), "{\"on\":true}", 200); // the filter chain clears the context
            bindPrincipal(clinic);
            assertThat(port.forTenant(clinic.tenantId()).hraOn()).isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ---------------- HRA ----------------

    @Test
    void hraOnThenOffPersistsOneRowAndVersionIncreases() throws Exception {
        Clinic clinic = newClinic();
        assertThat(ok(HttpMethod.PUT, HRA, clinic.owner(), "{\"on\":true}", 200).get("hra").get("on").asBoolean()).isTrue();
        assertThat(rows(clinic)).isEqualTo(1);
        long v1 = count(clinic, "select version from platform.module_entitlement");
        assertThat(ok(HttpMethod.GET, MODULES, clinic.as("BILLING"), null, 200).get("hra").get("on").asBoolean()).isTrue();

        assertThat(ok(HttpMethod.PUT, HRA, clinic.owner(), "{\"on\":false}", 200).get("hra").get("on").asBoolean()).isFalse();
        assertThat(rows(clinic)).isEqualTo(1);
        assertThat(count(clinic, "select version from platform.module_entitlement")).isGreaterThan(v1);
        assertThat(ok(HttpMethod.GET, MODULES, clinic.owner(), null, 200).get("hra").get("on").asBoolean()).isFalse();
    }

    @Test
    void invalidHraBodiesAreRefusedWithProblemDetailsAndNothingIsSaved() throws Exception {
        Clinic clinic = newClinic();
        JsonNode missing = problem(call(HttpMethod.PUT, HRA, clinic.owner(), "{}"), 400);
        assertThat(missing.toString()).contains("on");
        problem(call(HttpMethod.PUT, HRA, clinic.owner(), "{\"on\":null}"), 400);
        problem(call(HttpMethod.PUT, HRA, clinic.owner(), "{\"on\":\"maybe\"}"), 400);
        problem(call(HttpMethod.PUT, HRA, clinic.owner(), "{\"on\":[true]}"), 400);
        assertThat(rows(clinic)).isZero();
    }

    @Test
    void nonOwnersCannotChangeHra() throws Exception {
        Clinic clinic = newClinic();
        for (String role : NON_OWNERS) {
            problem(call(HttpMethod.PUT, HRA, clinic.as(role), "{\"on\":true}"), 403);
        }
        assertThat(rows(clinic)).isZero();
    }

    @Test
    void hraChangeIsAuditedWithBeforeAndAfterAndNoPatientData() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        ok(HttpMethod.PUT, HRA, clinic.owner(), "{\"on\":true}", 200);
        JsonNode e = auditEvent(clinic, "MODULE_HRA_CHANGED");
        assertThat(e).as("MODULE_HRA_CHANGED audit event").isNotNull();
        assertThat(e.get("before").get("hraOn").asBoolean()).isFalse();
        assertThat(e.get("after").get("hraOn").asBoolean()).isTrue();
        assertThat(e.toString()).doesNotContain("Ramesh", "Kulkarni", "Shivaji", patient.toString());
        assertThat(audits(clinic, "MODULE_HRA_CHANGED")).isEqualTo(1);
    }

    // ---------------- Plan mapping ----------------

    @Test
    void planColumnMapsToFreeAndPro() throws Exception {
        Clinic clinic = newClinic();
        assertThat(ok(HttpMethod.GET, MODULES, clinic.owner(), null, 200).get("plan").asText()).isEqualTo("FREE");
        commitAsTenant(clinic.tenantId(), "update platform.tenant set plan = 'PRO' where id = ?", clinic.tenantId());
        assertThat(ok(HttpMethod.GET, MODULES, clinic.owner(), null, 200).get("plan").asText()).isEqualTo("PRO");
    }

    // ---------------- Row level security ----------------

    @Test
    void anotherTenantHasItsOwnDefaultsAndCannotSeeOrAlterTheRow() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        ok(HttpMethod.PUT, HRA, a.owner(), "{\"on\":true}", 200);

        JsonNode mb = ok(HttpMethod.GET, MODULES, b.owner(), null, 200);
        assertThat(mb.get("hra").get("on").asBoolean()).isFalse();
        assertThat(mb.get("ipd").get("state").asText()).isEqualTo("off");
        assertThat(rows(b)).isZero();
        assertThat(rows(a)).isEqualTo(1);
        assertThat(ok(HttpMethod.GET, AUDIT, b.owner(), null, 200).toString()).doesNotContain("MODULE_");

        // Raw SQL as B: A's row is invisible, an update touches nothing, an insert for A is refused.
        assertThat(count(b, "select count(*) from platform.module_entitlement where tenant_id = ?", a.tenantId())).isZero();
        commitAsTenant(b.tenantId(), "update platform.module_entitlement set hra_on = false where tenant_id = ?", a.tenantId());
        assertThat(ok(HttpMethod.GET, MODULES, a.owner(), null, 200).get("hra").get("on").asBoolean()).isTrue();
        assertThatThrownBy(() -> db().inTenant(b.tenantId(), c -> {
            DbAccess.execute(c, "insert into platform.module_entitlement (tenant_id) values (?)", a.tenantId());
            return null;
        })).isInstanceOf(SQLException.class);

        // B changing its own state leaves A's row alone.
        ok(HttpMethod.PUT, HRA, b.owner(), "{\"on\":false}", 200);
        assertThat(ok(HttpMethod.GET, MODULES, a.owner(), null, 200).get("hra").get("on").asBoolean()).isTrue();
    }

    @Test
    void withNoTenantBoundTheAppRoleSeesNoRows() throws Exception {
        Clinic a = newClinic();
        ok(HttpMethod.PUT, HRA, a.owner(), "{\"on\":true}", 200);
        assertThat(rows(a)).isEqualTo(1);
        assertThat(db().<Long>asApp(c -> DbAccess.count(c, "select count(*) from platform.module_entitlement"))).isZero();
    }

    @Test
    void appRoleCannotDeleteEntitlementRows() throws Exception {
        Clinic a = newClinic();
        ok(HttpMethod.PUT, HRA, a.owner(), "{\"on\":true}", 200);
        assertDenied(a, "delete from platform.module_entitlement");
        assertDenied(a, "truncate platform.module_entitlement");
        assertThat(rows(a)).isEqualTo(1);
    }
}
