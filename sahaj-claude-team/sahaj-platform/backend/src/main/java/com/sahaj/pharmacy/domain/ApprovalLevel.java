package com.sahaj.pharmacy.domain;

/** Level 1 or 2 of an approval rule: who approves and when. */
public record ApprovalLevel(int level, ApproverKind approver, ApprovalCondition condition) {

    public String describe() {
        return approver + " " + condition.describe();
    }
}
