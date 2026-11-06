package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.WardRepository.RoomRow;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The one bed order shared by the admit dialog and the bed board. */
class BedOrderTest {

    private static final UUID WARD = UUID.randomUUID();
    private static final RoomRow FIRST = new RoomRow(UUID.randomUUID(), WARD, "Room 201");
    private static final RoomRow SECOND = new RoomRow(UUID.randomUUID(), WARD, "Room 101");

    private static BedRow bed(RoomRow room, String code) {
        return new BedRow(UUID.randomUUID(), WARD, room == null ? null : room.id(), code, "gen", "free");
    }

    @Test
    void ordersByRoomCreationOrderThenCodeWithBedsWithoutARoomLast() {
        List<BedRow> beds = new ArrayList<>(List.of(bed(null, "A-1"), bed(SECOND, "101-A"), bed(FIRST, "201-B"),
                bed(FIRST, "201-A"), bed(SECOND, "101-B")));

        beds.sort(BedOrder.byRoomThenCode(List.of(FIRST, SECOND)));

        assertThat(beds).extracting(BedRow::code).containsExactly("201-A", "201-B", "101-A", "101-B", "A-1");
    }

    @Test
    void aBedInAnUnknownRoomSortsWithTheBedsWithoutARoomAndTheirCodeDecides() {
        BedRow stray = new BedRow(UUID.randomUUID(), WARD, UUID.randomUUID(), "B-1", "gen", "free");
        List<BedRow> beds = new ArrayList<>(List.of(stray, bed(null, "A-1"), bed(FIRST, "201-A")));

        beds.sort(BedOrder.byRoomThenCode(List.of(FIRST)));

        assertThat(beds).extracting(BedRow::code).containsExactly("201-A", "A-1", "B-1");
    }
}
