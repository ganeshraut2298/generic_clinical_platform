package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.OrderNoticeRepository;
import com.sahaj.ipd.application.OrderVersionRepository.NewVersion;
import com.sahaj.ipd.application.PharmacistReviewRepository.Cursor;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.ReviewOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * SAH-76 the statements of the new order repositories (docs/database/SAH-76.md sections 1.5, 1.6 and 6): each insert
 * names exactly the granted columns, every stamp is left to the guard trigger, every read is by tenant, a timeline is
 * three set reads and a notice list needs no read per row. The statements run against the real database in the QA tests.
 */
class JdbcOrderRepositoriesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final StatementSpec spec = mock(StatementSpec.class, RETURNS_SELF);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> sqlSent(Runnable call) {
        JdbcClient.MappedQuerySpec mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(mapped.list()).thenReturn(List.of());
        org.mockito.Mockito.clearInvocations(jdbc);
        when(spec.query(any(RowMapper.class))).thenReturn(mapped);
        when(jdbc.sql(anyString())).thenReturn(spec);
        try {
            call.run();
        } catch (RuntimeException e) {
            // the row mapping is not under test; the mock returns nothing after the statement
        }
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).sql(sql.capture());
        return sql.getAllValues();
    }

    private String firstSql(Runnable call) {
        return sqlSent(call).get(0);
    }

    // ---- versions and holds ----

    @Test
    void theVersionInsertNamesOnlyTheGrantedColumnsAndReadsTheCreationTimeBack() {
        NewVersion v = new NewVersion(ID, 2, Kind.SCHEDULED, "bd", BigDecimal.ONE, "MG", "ORAL", NOW, null, "x",
                OrderPriority.ROUTINE, null, null, "why", ID, Verification.REQUIRED);

        String sql = firstSql(() -> new JdbcOrderVersionRepository(jdbc).insert(TENANT, v));

        assertThat(sql).contains("insert into ipd.medicine_order_version").contains("(order_id, version_no, kind, "
                + "frequency_key, dose_amount, dose_unit, route_key, start_at, end_at,")
                .contains("indication, priority, min_interval_min, max_per_24h, change_reason, created_by, verification)")
                .contains("returning created_at").doesNotContain("tenant_id").doesNotContain("superseded_at")
                .doesNotContain("verified_");
    }

    @Test
    void theStampsAreSentAsMarkersAndReadBackWithReturning() {
        String superseded = firstSql(() -> new JdbcOrderVersionRepository(jdbc).markSuperseded(TENANT, ID, 1));
        assertThat(superseded).contains("set superseded_at = clock_timestamp()").contains("superseded_at is null")
                .contains("tenant_id = :tenantId and order_id = :orderId and version_no = :versionNo");

        String verified = firstSql(() -> new JdbcOrderVersionRepository(jdbc).markVerified(TENANT, ID, 1, ID));
        assertThat(verified).contains("set verified_by = :user").contains("verified_by is null")
                .contains("returning verified_at").doesNotContain("set verified_at");
    }

    @Test
    void theHoldIsInsertedWithOnlyTheBusinessColumnsAndClosedByTheResumeColumnOnly() {
        String open = firstSql(() -> new JdbcOrderVersionRepository(jdbc).openHold(TENANT, ID, ID, "reason"));
        assertThat(open).contains("insert into ipd.order_hold (medicine_order_id, held_by, hold_reason)")
                .contains("returning held_at").doesNotContain("held_at,");

        String close = firstSql(() -> new JdbcOrderVersionRepository(jdbc).closeHold(TENANT, ID, ID));
        assertThat(close).contains("set resumed_by = :user").contains("resumed_at is null")
                .contains("medicine_order_id = :orderId").doesNotContain("set resumed_at");
    }

    // ---- the timeline ----

    @Test
    void theTimelineIsThreeSetReadsByAdmissionAndNeverOnePerOrder() {
        List<String> statements = sqlSent(() -> new JdbcOrderTimelineRepository(jdbc).findTimelines(TENANT,
                List.of(ID)));

        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).contains("from ipd.medicine_order o").contains("o.admission_id in (:admissionIds)");
        assertThat(statements.get(1)).contains("from ipd.medicine_order_version v")
                .contains("o.admission_id in (:admissionIds)").contains("order by v.order_id, v.version_no");
        assertThat(statements.get(2)).contains("from ipd.order_hold h").contains("o.admission_id in (:admissionIds)");
    }

    @Test
    void anEmptyAdmissionSetReadsNothing() {
        assertThat(new JdbcOrderTimelineRepository(jdbc).findTimelines(TENANT, List.of())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    // ---- queries ----

    @Test
    void theQueryInsertNamesTheFourColumnsAndTheCloseOnlyTheReplyColumns() {
        String insert = firstSql(() -> new JdbcOrderQueryRepository(jdbc).insert(TENANT, ID, 1, "q", ID));
        assertThat(insert).contains("insert into ipd.medicine_order_query (order_id, order_version, question, asked_by)")
                .contains("returning id, asked_at");

        String close = firstSql(() -> new JdbcOrderQueryRepository(jdbc).close(TENANT, ID, "REPLY", "r", ID));
        assertThat(close).contains("set reply = :reply, replied_by = :repliedBy, closed_by = :closedBy")
                .contains("closed_at is null").doesNotContain("closed_at =").doesNotContain("replied_at =");
    }

    @Test
    void theLatestQueryOfEachOrderIsOneStatement() {
        String sql = firstSql(() -> new JdbcOrderQueryRepository(jdbc).findLatest(TENANT, List.of(ID)));

        assertThat(sql).contains("select distinct on (q.order_id)").contains("q.order_id in (:orderIds)")
                .contains("order by q.order_id, q.asked_at desc");
    }

    // ---- reviews ----

    @Test
    void theReviewInsertNamesThreeColumnsAndIsIdempotentPerDose() {
        String sql = firstSql(() -> new JdbcPharmacistReviewRepository(jdbc).insert(TENANT, ID, 1, ID));

        assertThat(sql).contains("insert into ipd.pharmacist_review (order_id, order_version, dose_id)")
                .contains("on conflict (tenant_id, dose_id) do nothing");
    }

    @Test
    void theOpenListIsOldestFirstWithAKeysetOneJoinAndNoReadPerRow() {
        String first = firstSql(() -> new JdbcPharmacistReviewRepository(jdbc).findOpen(TENANT, null, 51));
        assertThat(first).contains("r.closed_at is null").contains("join ipd.medicine_order o")
                .contains("join ipd.dose_administration d").contains("join ipd.bed b")
                .contains("order by r.created_at, r.id limit :limit").doesNotContain("(r.created_at, r.id) >");

        String next = firstSql(() -> new JdbcPharmacistReviewRepository(jdbc).findOpen(TENANT,
                new Cursor(NOW, ID), 51));
        assertThat(next).contains("(r.created_at, r.id) > (:afterAt, :afterId)");
    }

    @Test
    void theReviewCloseNamesOnlyTheClosingUserAndTheOutcome() {
        String byDose = firstSql(() -> new JdbcPharmacistReviewRepository(jdbc).closeByDose(TENANT, ID, ID,
                ReviewOutcome.REVIEWED));
        assertThat(byDose).contains("set closed_by = :user, outcome = :outcome").contains("closed_at is null")
                .contains("dose_id = :doseId").doesNotContain("set closed_at");

        String byOrder = firstSql(() -> new JdbcPharmacistReviewRepository(jdbc).closeByOrder(TENANT, ID, ID,
                ReviewOutcome.VERIFIED));
        assertThat(byOrder).contains("order_id = :orderId").contains("closed_at is null");
    }

    // ---- notices ----

    private OrderNoticeRepository notices() {
        return new JdbcOrderNoticeRepository(jdbc);
    }

    @Test
    void theNoticeInsertNamesTheSixGrantedColumns() {
        String sql = firstSql(() -> notices().insert(TENANT, ID, ID, 1, "NEW", "STAT", ID));

        assertThat(sql).contains("insert into ipd.order_notice (admission_id, medicine_order_id, order_version, kind, "
                + "priority, created_by)").contains("returning id, created_at").doesNotContain("acknowledged");
    }

    @Test
    void anAcknowledgementNamesOnlyTheUserAndTheWayAndOnlyForAnOpenNotice() {
        String tap = firstSql(() -> notices().acknowledge(TENANT, ID, ID));
        assertThat(tap).contains("set acknowledged_by = :user, acknowledged_via = :via")
                .contains("acknowledged_at is null").doesNotContain("set acknowledged_at");

        String dose = firstSql(() -> notices().acknowledgeByOrder(TENANT, ID, ID));
        assertThat(dose).contains("acknowledged_via = 'DOSE'").contains("medicine_order_id = :orderId")
                .contains("acknowledged_at is null");
    }

    @Test
    void theTabIsTheOpenOnesStatFirstThenTheNewestAcknowledgedOnesWithALimit() {
        List<String> statements = sqlSent(() -> notices().findForAdmission(TENANT, ID, 100));

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("n.acknowledged_at is null")
                .contains("order by (n.priority = 'STAT') desc, n.created_at, n.id")
                .contains("join ipd.medicine_order o").contains("join ipd.admission a").contains("has_live_dose");
        assertThat(statements.get(1)).contains("n.acknowledged_at is not null").contains("limit :limit")
                .contains("order by n.acknowledged_at desc");
    }

    @Test
    void thePanelIsOpenNoticesOfAdmittedAdmissionsFilteredByTheScopeWithAKeyset() {
        String all = firstSql(() -> notices().findOpenPage(TENANT, null, null, 51));
        assertThat(all).contains("a.status = 'ADMITTED'").contains("n.acknowledged_at is null")
                .doesNotContain("n.admission_id in").contains("limit :limit")
                .contains("order by (case when n.priority = 'STAT' then 0 else 1 end), n.created_at, n.id");

        String scoped = firstSql(() -> notices().findOpenPage(TENANT, List.of(ID),
                new OrderNoticeRepository.Cursor(1, NOW, ID), 51));
        assertThat(scoped).contains("n.admission_id in (:admissionIds)").contains("> (:rank, :afterAt, :afterId)");
    }

    @Test
    void theBadgeNumbersAreOneGroupedStatementAndAnEmptyScopeReadsNothing() {
        String sql = firstSql(() -> notices().stats(TENANT, null, NOW, NOW));
        assertThat(sql).contains("group by n.admission_id").contains("filter (where n.priority = 'STAT')")
                .contains("n.created_at <= :statCut").contains("n.created_at <= :cut").contains("a.status = 'ADMITTED'");

        JdbcClient quiet = mock(JdbcClient.class);
        assertThat(new JdbcOrderNoticeRepository(quiet).stats(TENANT, List.of(), NOW, NOW)).isEmpty();
        assertThat(new JdbcOrderNoticeRepository(quiet).openDigest(TENANT, List.of()))
                .isEqualTo(OrderNoticeRepository.EMPTY_DIGEST);
        assertThat(new JdbcOrderNoticeRepository(quiet).countOpen(TENANT, List.of())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(quiet);
    }

    @Test
    void thePollTokenIsOneHashComputedInTheDatabaseAndNoIdListIsRead() {
        String all = firstSql(() -> notices().openDigest(TENANT, null));
        assertThat(all).contains("md5(coalesce(string_agg(n.id::text, ',' order by n.id), ''))")
                .contains("a.status = 'ADMITTED'").contains("n.acknowledged_at is null")
                .contains("n.tenant_id = :tenantId").doesNotContain("n.admission_id in").doesNotContain("select n.id");

        String scoped = firstSql(() -> notices().openDigest(TENANT, List.of(ID)));
        assertThat(scoped).contains("n.admission_id in (:admissionIds)");
    }

    @Test
    void theAcknowledgementStateComesFromOneGroupedStatementOfTheAdmission() {
        String sql = firstSql(() -> notices().findAckStates(TENANT, ID));

        assertThat(sql).contains("n.tenant_id = :tenantId and n.admission_id = :admissionId")
                .contains("group by n.medicine_order_id").contains("n.kind in ('NEW', 'CHANGED')");
    }
}
