package com.sahaj.ipd.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * SAH-22 response of every ipd endpoint (contract {@code IpdLayout}): the whole bed layout, the four bed
 * categories and the daily charges. Money is integer paise.
 */
public record IpdLayout(String state, boolean roomLevel, int bedLimit, int bedCount, List<Ward> wards,
                        List<Category> categories, Charges charges) {

    public record Bed(UUID id, String code, String categoryKey, String status) {
    }

    public record Room(UUID id, String name, List<Bed> beds) {
    }

    public record Ward(UUID id, String name, String station, int bedCount, List<Room> rooms, List<Bed> beds) {
    }

    public record Category(String key, String name, long ratePaise, BigDecimal multiplier, int beds) {
    }

    public record Charges(String bedDayCut, long nursingPaise, long visitPaise) {
    }
}
