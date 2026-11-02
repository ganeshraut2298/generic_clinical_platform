package com.sahaj.opd.application;

/** Fields of {@code POST /services} (all needed) and {@code PATCH /services/{id}} (only those sent) in SAH-7. */
public record ServiceInput(String name, String category, Integer priceRupees, Boolean active) {
}
