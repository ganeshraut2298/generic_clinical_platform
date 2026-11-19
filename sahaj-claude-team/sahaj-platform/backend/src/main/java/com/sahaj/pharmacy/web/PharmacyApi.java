package com.sahaj.pharmacy.web;

/**
 * Marks the controllers of docs/api/pharmacy.yaml so that {@link PharmacyProblemHandler} answers their own problem types
 * (settings-changed with {@code current}, store-has-stock with {@code lockedFields}) and the unreadable-body case, and
 * nothing else in the application.
 */
public interface PharmacyApi {
}
