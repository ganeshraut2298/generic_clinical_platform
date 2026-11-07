package com.sahaj.ipd.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SAH-36 code review R36-8: the Admitted list with placement is the Admitted list plus two columns and one left join,
 * with the same filter and the same order, so the two reads cannot drift apart. No database.
 */
class JdbcAdmissionQueriesTest {

    @Test
    void thePlacementListIsTheAdmittedListPlusTheWardIdTheRoomNameAndTheRoomJoin() {
        String reduced = JdbcAdmissionRepository.FIND_ADMITTED_WITH_PLACEMENT
                .replace(",\n       b.ward_id as ward_id, r.name as room_name", "")
                .replace("left join ipd.room r on r.tenant_id = b.tenant_id and r.id = b.room_id\n", "");

        assertThat(reduced).isEqualTo(JdbcAdmissionRepository.FIND_ADMITTED);
    }

    @Test
    void bothListsKeepTheOneAdmittedFilterAndTheNewestFirstOrder() {
        for (String sql : new String[] {JdbcAdmissionRepository.FIND_ADMITTED,
                JdbcAdmissionRepository.FIND_ADMITTED_WITH_PLACEMENT}) {
            assertThat(sql).contains("where a.tenant_id = :tenantId and a.status = 'ADMITTED'")
                    .endsWith("order by a.admitted_at desc, a.id\n");
        }
    }

    @Test
    void thePlacementListAddsItsColumnsAfterTheAdmissionColumns() {
        String sql = JdbcAdmissionRepository.FIND_ADMITTED_WITH_PLACEMENT;

        assertThat(sql.indexOf("as deposit_total")).isPositive()
                .isLessThan(sql.indexOf("b.ward_id as ward_id"));
        assertThat(sql.indexOf("b.ward_id as ward_id")).isLessThan(sql.indexOf("from ipd.admission a"));
        assertThat(sql).contains("left join ipd.room r");
    }
}
