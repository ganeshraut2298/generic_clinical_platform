package com.sahaj.ipd.application;

import java.util.List;
import java.util.UUID;

/** SAH-24 bed board response shapes (contract {@code BedBoard}). Patient names appear on screen only. */
public final class BedBoardViews {

    private BedBoardViews() {
    }

    /** {@code toString} leaves out the patient name. */
    public record BoardBed(UUID id, String code, String categoryKey, String categoryName, long ratePaise,
                           String status, UUID admissionId, String patientName, Integer dayNumber,
                           String doctorShortName, Boolean depositLow, Boolean goingHomeToday) {

        @Override
        public String toString() {
            return "BoardBed[id=" + id + ", code=" + code + ", status=" + status + "]";
        }
    }

    public record BoardRoom(UUID id, String name, List<BoardBed> beds) {
    }

    public record BoardWard(UUID id, String name, String station, int occupied, int total, boolean hasFreeBed,
                            UUID firstFreeBedId, List<BoardRoom> rooms) {
    }

    public record Counts(int free, int occ, int clean, int res, int blk) {
    }

    public record BedBoard(String state, boolean roomLevel, boolean canAdmit, boolean canChangeBeds, Counts counts,
                           List<BoardWard> wards) {

        @Override
        public String toString() {
            return "BedBoard[state=" + state + ", wards=" + wards.size() + "]";
        }
    }
}
