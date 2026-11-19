package com.sahaj.pharmacy.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ADR-028 D4 (the {@code x-store-access} marker of docs/api/pharmacy.yaml): every handler that works on a store, that is
 * every handler under {@code /stores/{storeId}/**} and the working-store operation, declares exactly one kind.
 * {@code PharmacyStoreAccessCoverageTest} fails when one does not, and checks each kind against the contract.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface StoreAccess {

    Kind value();

    enum Kind {
        /**
         * The store is read or changed as a master record: gated by the access level (SETUP_VIEW or SETUP_EDIT), never by a
         * mapping. 404 only for an unknown or another clinic's id.
         */
        MASTER,
        /**
         * The operation acts inside the store: its service calls {@code StoreAccessGuard} (404 for a store the caller is not
         * mapped to, 403 {@code store-permission-denied} without the capability).
         */
        OPERATION,
        /**
         * SAH-79 (rule 15): the operation is not under a store path and lists, for the caller, the stores the caller can reach
         * (the owner every ACTIVE store, anyone else the stores they are mapped to). It names no store, so no store is refused.
         */
        SCOPED
    }
}
