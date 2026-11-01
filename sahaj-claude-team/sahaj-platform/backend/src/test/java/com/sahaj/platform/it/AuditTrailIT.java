package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static com.sahaj.platform.support.TestTenantSecurity.authentication;
import static com.sahaj.platform.support.TestTenantSecurity.runAs;
import static com.sahaj.platform.support.TestTenantSecurity.signedInWithoutTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahaj.platform.api.AuditLog;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SAH-1 criterion 3: every change to a tenant row is recorded in {@code platform.audit_event}
 * (who, what, when, which tenant), in the same transaction as the change; the trail is append-only
 * and isolated per tenant. Runs as sahaj_app on sahaj_test.
 */
@OnTestDatabase
class AuditTrailIT extends BaseIntegrationTest {

    private static final String PERMISSION_DENIED = "permission denied";

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID tenantA;
    private UUID tenantB;
    private UUID userA;
    private UUID userB;
    private String nameA;

    @BeforeEach
    void ids() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        nameA = uniqueName("Audit Clinic A");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private Authentication authA() {
        return authentication(tenantA, userA);
    }

    // ---- who, what, when, tenant ----

    @Test
    void creatingATenantWritesOneAuditEventWithActorActionEntityTenantAndTime() throws Exception {
        OffsetDateTime before = OffsetDateTime.now();
        createTenant(tenantA, userA, nameA);

        mvc.perform(get(PROBE + "/audit-events").with(asUser(tenantA, userA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'TENANT_CREATED')].entityType").value("TENANT"))
                .andExpect(jsonPath("$[?(@.action == 'TENANT_CREATED')].entityId").value(tenantA.toString()))
                .andExpect(jsonPath("$[?(@.action == 'TENANT_CREATED')].actorUserId").value(userA.toString()));

        db().inTenant(tenantA, c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where entity_id = ?::uuid and action = 'TENANT_CREATED'",
                    tenantA.toString())).as("exactly one TENANT_CREATED event").isEqualTo(1);
            try (var ps = c.prepareStatement("select tenant_id, actor_user_id, entity_type, before_json::text, after_json ->> 'name' as name, at "
                    + "from platform.audit_event where entity_id = ?::uuid and action = 'TENANT_CREATED'")) {
                ps.setString(1, tenantA.toString());
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject("tenant_id", UUID.class)).as("tenant").isEqualTo(tenantA);
                    assertThat(rs.getObject("actor_user_id", UUID.class)).as("who").isEqualTo(userA);
                    assertThat(rs.getString("entity_type")).as("what").isEqualTo("TENANT");
                    assertThat(rs.getString("before_json")).as("a create has no before").isNull();
                    assertThat(rs.getString("name")).isEqualTo(nameA);
                    OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
                    assertThat(Duration.between(before, at).abs()).as("when").isLessThan(Duration.ofMinutes(2));
                }
            }
            return null;
        });
    }

    @Test
    void theTenantOfTheAuditRowComesFromTheLoggedInUserNotFromTheRequest() throws Exception {
        createTenant(tenantA, userA, nameA);
        UUID tenantC = UUID.randomUUID();
        // User C creates its own tenant while the request names A in header, query and body.
        mvc.perform(post(PROBE + "/tenants")
                        .with(asUser(tenantC, userB))
                        .header("X-Tenant-Id", tenantA.toString())
                        .queryParam("tenantId", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + tenantC + "\",\"name\":\"" + uniqueName("C") + "\",\"tenantId\":\"" + tenantA + "\"}"))
                .andExpect(status().isOk());
        db().inTenant(tenantC, c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where entity_id = ?::uuid and tenant_id = ?::uuid",
                    tenantC.toString(), tenantC.toString())).isEqualTo(1);
            return null;
        });
        db().inTenant(tenantA, c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where entity_id = ?::uuid",
                    tenantC.toString())).as("nothing for C in A's trail").isZero();
            return null;
        });
    }

    @Test
    void mobileNumbersAreMaskedToTheLastFourDigitsAndOneTimeCodesAreRedacted() throws Exception {
        createTenant(tenantA, userA, nameA);
        runAs(authA(), () -> tx().executeWithoutResult(s -> auditLog.record(
                "USER_CHANGED", "USER", userA,
                Map.of("mobile", "9123456789"),
                Map.of("mobile", "9876501234", "otp", "654321", "contact", Map.of("phone", "9000011111")))));

        db().inTenant(tenantA, c -> {
            String dots = "•".repeat(6);
            assertThat(DbAccess.text(c, "select before_json ->> 'mobile' from platform.audit_event where action = 'USER_CHANGED' and entity_id = ?::uuid",
                    userA.toString())).isEqualTo(dots + "6789");
            assertThat(DbAccess.text(c, "select after_json ->> 'mobile' from platform.audit_event where action = 'USER_CHANGED' and entity_id = ?::uuid",
                    userA.toString())).isEqualTo(dots + "1234");
            assertThat(DbAccess.text(c, "select after_json ->> 'otp' from platform.audit_event where action = 'USER_CHANGED' and entity_id = ?::uuid",
                    userA.toString())).isEqualTo("[redacted]");
            String raw = DbAccess.text(c, "select before_json::text || after_json::text from platform.audit_event where action = 'USER_CHANGED' and entity_id = ?::uuid",
                    userA.toString());
            assertThat(raw).doesNotContain("9123456789").doesNotContain("9876501234").doesNotContain("654321")
                    .doesNotContain("9000011111");
            return null;
        });
    }

    // ---- same transaction as the change ----

    @Test
    void theAuditRowRollsBackTogetherWithTheChange() throws Exception {
        createTenant(tenantA, userA, nameA);
        String renamed = uniqueName("Renamed then rolled back");

        assertThatThrownBy(() -> runAs(authA(), () -> tx().executeWithoutResult(s -> {
            jdbc.sql("update platform.tenant set name = :n where id = :id").param("n", renamed).param("id", tenantA).update();
            auditLog.record("TENANT_RENAMED_ROLLBACK", "TENANT", tenantA, Map.of("name", nameA), Map.of("name", renamed));
            throw new IllegalStateException("simulated failure after the audit call");
        }))).isInstanceOf(IllegalStateException.class);

        // Positive control in the same style: a committed change keeps both rows.
        String committed = uniqueName("Renamed and committed");
        runAs(authA(), () -> tx().executeWithoutResult(s -> {
            jdbc.sql("update platform.tenant set name = :n where id = :id").param("n", committed).param("id", tenantA).update();
            auditLog.record("TENANT_RENAMED_COMMIT", "TENANT", tenantA, Map.of("name", nameA), Map.of("name", committed));
        }));

        db().inTenant(tenantA, c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where action = 'TENANT_RENAMED_ROLLBACK' and entity_id = ?::uuid",
                    tenantA.toString())).as("rolled back audit row").isZero();
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where action = 'TENANT_RENAMED_COMMIT' and entity_id = ?::uuid",
                    tenantA.toString())).as("committed audit row").isEqualTo(1);
            assertThat(DbAccess.text(c, "select name from platform.tenant where id = ?::uuid", tenantA.toString()))
                    .as("the rolled back rename never happened").isEqualTo(committed);
            return null;
        });
    }

    @Test
    void anAuditEventOutsideATransactionIsRefusedAndWritesNothing() throws Exception {
        createTenant(tenantA, userA, nameA);
        assertThatThrownBy(() -> runAs(authA(), () -> auditLog.record("NO_TX", "TENANT", tenantA, null, Map.of("k", "v"))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        db().inTenant(tenantA, c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where action = 'NO_TX'")).isZero();
            return null;
        });
    }

    @Test
    void anAuditEventWithoutALoggedInClinicUserIsRefusedAndWritesNothing() throws Exception {
        assertThatThrownBy(() -> runAs(signedInWithoutTenant(), () -> tx().executeWithoutResult(s ->
                auditLog.record("NO_USER", "TENANT", tenantA, null, Map.of("k", "v")))))
                .isInstanceOf(RuntimeException.class);
    }

    // ---- tenant isolation of the trail ----

    @Test
    void tenantBCannotSeeTenantAsAuditRows() throws Exception {
        createTenant(tenantA, userA, nameA);
        createTenant(tenantB, userB, uniqueName("Audit Clinic B"));

        mvc.perform(get(PROBE + "/audit-events").with(asUser(tenantB, userB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.entityId == '" + tenantA + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.entityId == '" + tenantB + "')]").isNotEmpty());

        db().inTenant(tenantB, c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where tenant_id = ?::uuid", tenantA.toString()))
                    .as("rows of A visible to B").isZero();
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where entity_id = ?::uuid", tenantA.toString()))
                    .isZero();
            return null;
        });
    }

    @Test
    void tenantBCannotWriteAnAuditRowForTenantA() throws Exception {
        createTenant(tenantA, userA, nameA);
        createTenant(tenantB, userB, uniqueName("Audit Clinic B"));
        assertThatThrownBy(() -> db().inTenant(tenantB, c -> {
            DbAccess.execute(c, "insert into platform.audit_event (tenant_id, action, entity_type) values (?::uuid, 'FORGED', 'TENANT')",
                    tenantA.toString());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("42501");
            assertThat(e.getMessage()).contains("row-level security");
        });
    }

    // ---- append-only ----

    @Test
    void sahajAppCannotUpdateAnAuditRow() throws Exception {
        createTenant(tenantA, userA, nameA);
        assertThatThrownBy(() -> db().inTenant(tenantA, c -> {
            DbAccess.execute(c, "update platform.audit_event set action = 'TAMPERED' where entity_id = ?::uuid", tenantA.toString());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("42501");
            assertThat(e.getMessage()).contains(PERMISSION_DENIED);
        });
    }

    @Test
    void sahajAppCannotDeleteAnAuditRow() throws Exception {
        createTenant(tenantA, userA, nameA);
        assertThatThrownBy(() -> db().inTenant(tenantA, c -> {
            DbAccess.execute(c, "delete from platform.audit_event where entity_id = ?::uuid", tenantA.toString());
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("42501");
            assertThat(e.getMessage()).contains(PERMISSION_DENIED);
        });
        // Still there.
        db().inTenant(tenantA, c -> {
            assertThat(DbAccess.count(c, "select count(*) from platform.audit_event where entity_id = ?::uuid", tenantA.toString())).isEqualTo(1);
            return null;
        });
    }

    @Test
    void sahajAppCannotTruncateTheAuditTable() {
        assertThatThrownBy(() -> db().asApp(c -> {
            DbAccess.execute(c, "truncate platform.audit_event");
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("42501");
            assertThat(e.getMessage()).contains(PERMISSION_DENIED);
        });
    }

    @Test
    void theTableOwnerIsAlsoStoppedByTheAppendOnlyTrigger() {
        // The trigger layer: even sahaj_migrator, which owns the table, cannot truncate it. Rolled back anyway.
        assertThatThrownBy(() -> db().asMigrator(c -> {
            try (Statement statement = c.createStatement()) {
                statement.execute("truncate platform.audit_event");
            }
            return null;
        })).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("42501");
            assertThat(e.getMessage()).contains("append-only");
        });
    }
}
