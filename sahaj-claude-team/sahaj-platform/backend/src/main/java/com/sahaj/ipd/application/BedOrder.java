package com.sahaj.ipd.application;

import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.WardRepository.RoomRow;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The one bed order shared by the admit dialog and the bed board (SAH-26 code review). */
final class BedOrder {

    private BedOrder() {
    }

    /**
     * Room in the order the rooms were created (beds without a room last), then bed code. {@code rooms} must be in
     * creation order, as {@link WardRepository#findRooms} returns them.
     */
    static Comparator<BedRow> byRoomThenCode(List<RoomRow> rooms) {
        Map<UUID, Integer> roomOrder = new HashMap<>();
        for (RoomRow room : rooms) {
            roomOrder.put(room.id(), roomOrder.size());
        }
        return Comparator
                .comparingInt((BedRow b) -> b.roomId() == null ? Integer.MAX_VALUE
                        : roomOrder.getOrDefault(b.roomId(), Integer.MAX_VALUE))
                .thenComparing(BedRow::code);
    }
}
