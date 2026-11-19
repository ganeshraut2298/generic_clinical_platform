package com.sahaj.pharmacy.domain;

/** Who approves at a level: the HOD of the transaction's store (or its current delegate), or the owner. */
public enum ApproverKind {
    STORE_APPROVER,
    OWNER
}
