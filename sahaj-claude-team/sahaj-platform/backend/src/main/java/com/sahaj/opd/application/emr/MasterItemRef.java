package com.sahaj.opd.application.emr;

import java.util.Objects;

/**
 * A reference to one item of a master: the master and the item key (the uuid as text for complaints,
 * investigations, advice and medicines; the ICD-10 code for diagnoses). It is the only thing
 * {@link EmrUsageRecorder} accepts, so free text a doctor typed has no way in (BR7). The key is checked against the
 * master by the recorder; an item that is not in its master is ignored.
 */
public record MasterItemRef(EmrMaster master, String itemKey) {

    public static final int MAX_KEY_LENGTH = 40;

    public MasterItemRef {
        Objects.requireNonNull(master, "master");
        Objects.requireNonNull(itemKey, "itemKey");
        if (itemKey.isEmpty() || itemKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("An item key is 1 to 40 characters.");
        }
    }
}
