package com.sahaj.pharmacy.domain;

/** The preset a set of approval rules was last saved as. A save by hand is CUSTOM; only LEAN, STANDARD and STRICT can be applied. */
public enum RulePreset {
    LEAN,
    STANDARD,
    STRICT,
    CUSTOM
}
