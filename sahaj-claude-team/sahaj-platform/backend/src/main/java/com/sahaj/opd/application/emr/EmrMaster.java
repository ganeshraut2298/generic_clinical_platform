package com.sahaj.opd.application.emr;

import java.util.Arrays;
import java.util.Optional;

/**
 * The five quick-option masters of the Doctor EMR (SAH-43, ADR-019 D3). {@code key} is the value used in the API
 * paths and in {@code opd.emr_item_usage.master}. Complaints, investigations and advice are searched under
 * {@code /emr/masters}; diagnoses and medicines keep their SAH-7 search.
 */
public enum EmrMaster {
    COMPLAINTS("complaints", true),
    DIAGNOSES("diagnoses", false),
    MEDICINES("medicines", false),
    INVESTIGATIONS("investigations", true),
    ADVICE("advice", true);

    private final String key;
    private final boolean searchable;

    EmrMaster(String key, boolean searchable) {
        this.key = key;
        this.searchable = searchable;
    }

    public String key() {
        return key;
    }

    /** True for the three masters searched with {@code GET /emr/masters/{master}}. */
    public boolean searchable() {
        return searchable;
    }

    public static Optional<EmrMaster> fromKey(String key) {
        return Arrays.stream(values()).filter(master -> master.key.equals(key)).findFirst();
    }
}
