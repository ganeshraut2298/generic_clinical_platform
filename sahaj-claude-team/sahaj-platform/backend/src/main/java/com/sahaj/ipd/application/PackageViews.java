package com.sahaj.ipd.application;

import java.util.List;
import java.util.UUID;

/** SAH-29 response shapes of {@code docs/api/ipd.yaml} ({@code PackageList}, {@code IpdPackage}). */
public final class PackageViews {

    private PackageViews() {
    }

    /** The answer of {@code GET /packages}. */
    public record PackageList(List<IpdPackage> items) {
    }

    /** One row of the Packages panel. A package name is not patient data. */
    public record IpdPackage(UUID id, String name, long pricePaise, int includedDays) {
    }
}
