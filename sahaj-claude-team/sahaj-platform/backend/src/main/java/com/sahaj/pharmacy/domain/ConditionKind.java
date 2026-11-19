package com.sahaj.pharmacy.domain;

/** When a level applies (docs/api/pharmacy.yaml ApprovalCondition.kind). */
public enum ConditionKind {
    ALWAYS,
    ABOVE_AMOUNT,
    ABOVE_PERCENT,
    SCHEDULE_X_ITEM,
    HIGH_VALUE_ITEM,
    URGENT_PRIORITY,
    STORE_TYPE
}
