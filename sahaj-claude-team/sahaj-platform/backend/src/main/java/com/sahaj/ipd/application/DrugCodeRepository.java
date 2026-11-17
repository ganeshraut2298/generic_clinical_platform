package com.sahaj.ipd.application;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * SAH-76-4 (ADR-027 D9) {@code ipd.drug_code}: the global medicine codes of the drug master (shipped empty, select
 * only). Read-only; the codes are loaded by a reviewed migration.
 */
public interface DrugCodeRepository {

    /** The normalised codes of one drug. */
    Set<String> codesOf(String drugKey);

    /** The drug of a normalised code, when the code is in the drug master. */
    Optional<String> drugKeyOf(String code);

    /** The keys among {@code drugKeys} that have at least one code (one statement). */
    Set<String> drugKeysWithCodes(Collection<String> drugKeys);
}
