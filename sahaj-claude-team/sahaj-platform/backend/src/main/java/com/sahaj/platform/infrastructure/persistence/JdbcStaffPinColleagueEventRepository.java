package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.StaffPinColleagueEventRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code platform.staff_pin_colleague_event} (SAH-38, ADR-014 D2). The statements are the ones in
 * {@code docs/database/SAH-38.md} section 4. Inserts never name {@code id}, {@code tenant_id} or {@code at}
 * (defaults and the insert guard; naming them is SQLSTATE 42501). Times that decide anything are the database clock.
 * Row level security limits every statement to the bound clinic; the tenant id is also in each query. Nothing here
 * logs or returns a PIN, a hash or a name.
 */
@Repository
public class JdbcStaffPinColleagueEventRepository implements StaffPinColleagueEventRepository {

    private static final String COUNT_PAIR = """
            select count(*) from platform.staff_pin_colleague_event
             where tenant_id = :t and membership_id = :b and by_membership_id = :a
               and kind = 'WRONG'
               and at > greatest(statement_timestamp() - interval '7 days', cast(:setAt as timestamptz),
                     coalesce((select max(at) from platform.staff_pin_colleague_event
                                where tenant_id = :t and membership_id = :b and by_membership_id = :a
                                  and kind = 'RIGHT_AFTER_WRONG'), cast('-infinity' as timestamptz)))
            """;
    private static final String INSERT_WRONG = """
            insert into platform.staff_pin_colleague_event (membership_id, by_membership_id, kind, created_by)
            values (:b, :a, 'WRONG', :u)
            """;
    private static final String INSERT_LOCKED = """
            insert into platform.staff_pin_colleague_event (membership_id, kind, created_by)
            values (:b, 'LOCKED', :u)
            """;
    private static final String INSERT_RIGHT_AFTER_WRONG = """
            insert into platform.staff_pin_colleague_event (membership_id, by_membership_id, kind, created_by)
            values (:b, :a, 'RIGHT_AFTER_WRONG', :u)
            """;
    private static final String INSERT_NOTICE_SEEN = """
            insert into platform.staff_pin_colleague_event (membership_id, kind, seen_through, created_by)
            values (:m, 'NOTICE_SEEN', :through, :u)
            """;
    // Empty-safe: a clinic member who never tapped "Got it" has no NOTICE_SEEN row, and "select max(...)" would still
    // answer one row holding null (JdbcClient.single() refuses it). Read the newest row, if any (QA defect B1).
    private static final String LATEST_SEEN = """
            select seen_through from platform.staff_pin_colleague_event
             where tenant_id = :t and membership_id = :m and kind = 'NOTICE_SEEN' and seen_through is not null
             order by seen_through desc
             limit 1
            """;
    private static final String UNSEEN = """
            select count(*) as n, max(at) as latest from platform.staff_pin_colleague_event
             where tenant_id = :t and membership_id = :m and kind = 'WRONG' and at > cast(:after as timestamptz)
            """;
    private static final String EXISTS_WRONG_BETWEEN = """
            select exists (select 1 from platform.staff_pin_colleague_event
             where tenant_id = :t and membership_id = :m and kind = 'WRONG'
               and at > cast(:after as timestamptz) and at <= cast(:through as timestamptz))
            """;
    // QA defect B2: the 7-day windows of the reads use statement_timestamp() (the database clock, STABLE), not the
    // VOLATILE clock_timestamp(). The planner can only fold a stable expression into the index range of
    // ix_staff_pin_colleague_event_wrong_7d; with clock_timestamp() it guessed a third of the table and scanned it all.
    // The two differ by the milliseconds the statement takes, so no row near the window edge matters. The pair cap
    // (COUNT_PAIR) uses statement_timestamp() too, so "at" becomes an Index Cond of
    // ix_staff_pin_colleague_event_pair_kind. It is safe there: StaffPinOps.checkColleague takes the colleague's
    // staff_pin row lock in an earlier statement (find(..., true) = FIND_FOR_UPDATE) of the same transaction, so the
    // count starts after the lock and its "now" is off only by the count's own running milliseconds. The window start
    // is still the latest of 7 days ago, the PIN set_at and the last RIGHT_AFTER_WRONG. Stored times and lock
    // decisions (locked_until, the "at" stamps, AS_OF) keep clock_timestamp().
    private static final String WRONG_7D_BY_MEMBER = """
            select membership_id, count(*) as n from platform.staff_pin_colleague_event
             where tenant_id = :t and kind = 'WRONG' and at > statement_timestamp() - interval '7 days'
             group by membership_id
            """;
    private static final String WRONG_7D_OF_MEMBER = """
            select count(*) from platform.staff_pin_colleague_event
             where tenant_id = :t and membership_id = :m and kind = 'WRONG'
               and at > statement_timestamp() - interval '7 days'
            """;
    private static final String AS_OF = "select clock_timestamp()";
    private static final String HISTORY_WRONG_COUNT = """
            select count(*) from platform.staff_pin_colleague_event
             where tenant_id = :t and membership_id = :m and kind = 'WRONG'
               and at > cast(:asOf as timestamptz) - interval '7 days' and at <= cast(:asOf as timestamptz)
            """;
    /** WRONG rows grouped by caller and IST calendar day; every other row on its own (grouped by its id). */
    private static final String HISTORY = """
            select kind, max(at) as at, by_membership_id, count(*) as n
              from platform.staff_pin_colleague_event
             where tenant_id = :t and membership_id = :m and kind <> 'NOTICE_SEEN'
               and at > cast(:asOf as timestamptz) - interval '7 days' and at <= cast(:asOf as timestamptz)
             group by kind, by_membership_id,
                      case when kind = 'WRONG' then cast(at at time zone 'Asia/Kolkata' as date) end,
                      case when kind <> 'WRONG' then id end
             order by max(at) desc, kind
             limit :limit
            """;

    private final JdbcClient jdbc;

    public JdbcStaffPinColleagueEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int countPairWrongTries(UUID tenantId, UUID membershipId, UUID byMembershipId, Instant pinSetAt) {
        return jdbc.sql(COUNT_PAIR).param("t", tenantId).param("b", membershipId).param("a", byMembershipId)
                .param("setAt", pinSetAt).query(Integer.class).single();
    }

    @Override
    public void insertWrong(UUID membershipId, UUID byMembershipId, UUID createdBy) {
        jdbc.sql(INSERT_WRONG).param("b", membershipId).param("a", byMembershipId).param("u", createdBy).update();
    }

    @Override
    public void insertLocked(UUID membershipId, UUID createdBy) {
        jdbc.sql(INSERT_LOCKED).param("b", membershipId).param("u", createdBy).update();
    }

    @Override
    public void insertRightAfterWrong(UUID membershipId, UUID byMembershipId, UUID createdBy) {
        jdbc.sql(INSERT_RIGHT_AFTER_WRONG).param("b", membershipId).param("a", byMembershipId)
                .param("u", createdBy).update();
    }

    @Override
    public Optional<Instant> latestSeenThrough(UUID tenantId, UUID membershipId) {
        return jdbc.sql(LATEST_SEEN).param("t", tenantId).param("m", membershipId)
                .query((rs, rowNum) -> rs.getTimestamp(1).toInstant()).optional();
    }

    @Override
    public Unseen unseen(UUID tenantId, UUID membershipId, Instant after) {
        return jdbc.sql(UNSEEN).param("t", tenantId).param("m", membershipId).param("after", after)
                .query((rs, rowNum) -> new Unseen(rs.getInt("n"), instant(rs.getTimestamp("latest")))).single();
    }

    @Override
    public boolean existsWrongBetween(UUID tenantId, UUID membershipId, Instant after, Instant through) {
        return jdbc.sql(EXISTS_WRONG_BETWEEN).param("t", tenantId).param("m", membershipId).param("after", after)
                .param("through", through).query(Boolean.class).single();
    }

    @Override
    public void insertNoticeSeen(UUID membershipId, Instant seenThrough, UUID createdBy) {
        jdbc.sql(INSERT_NOTICE_SEEN).param("m", membershipId).param("through", seenThrough).param("u", createdBy)
                .update();
    }

    @Override
    public Map<UUID, Integer> wrongTriesLast7Days(UUID tenantId) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        jdbc.sql(WRONG_7D_BY_MEMBER).param("t", tenantId)
                .query((rs, rowNum) -> counts.put(rs.getObject("membership_id", UUID.class), rs.getInt("n"))).list();
        return counts;
    }

    @Override
    public int wrongTriesLast7Days(UUID tenantId, UUID membershipId) {
        return jdbc.sql(WRONG_7D_OF_MEMBER).param("t", tenantId).param("m", membershipId).query(Integer.class)
                .single();
    }

    @Override
    public History history(UUID tenantId, UUID membershipId, int limit) {
        Instant asOf = jdbc.sql(AS_OF).query((rs, rowNum) -> instant(rs.getTimestamp(1))).single();
        int wrong = jdbc.sql(HISTORY_WRONG_COUNT).param("t", tenantId).param("m", membershipId).param("asOf", asOf)
                .query(Integer.class).single();
        List<Line> lines = jdbc.sql(HISTORY).param("t", tenantId).param("m", membershipId).param("asOf", asOf)
                .param("limit", limit).query(JdbcStaffPinColleagueEventRepository::line).list();
        return new History(asOf, lines, wrong);
    }

    private static Line line(ResultSet rs, int rowNum) throws SQLException {
        return new Line(rs.getString("kind"), instant(rs.getTimestamp("at")),
                rs.getObject("by_membership_id", UUID.class), rs.getInt("n"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
