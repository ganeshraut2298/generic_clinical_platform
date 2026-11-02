package com.sahaj.opd.application;

import java.util.UUID;

/** A service as the API shows it: the price in whole rupees. */
public record ServiceView(UUID id, String name, String category, long priceRupees, boolean active) {
}
