package com.sahaj.platform.devtools.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One warning when the endpoint is on (ADR-008 D2, R1). It carries the per-start nonce and no other secret: no email,
 * no password, no key, no URL with a secret. This is the only place the nonce is ever printed.
 */
final class DevSampleAdminStartupNotice {

    private static final Logger log = LoggerFactory.getLogger(DevSampleAdminStartupNotice.class);

    static final String MESSAGE_FORMAT = "Dev-only sample admin endpoint is on (dev-tools build). POST "
            + DevSampleAdminGate.PATH + " from this machine creates an admin once. Send header "
            + DevSampleAdminNonce.HEADER + ": {}. The value changes at every start.";

    DevSampleAdminStartupNotice(DevSampleAdminNonce nonce) {
        log.warn(MESSAGE_FORMAT, nonce.valueForStartupNotice());
    }
}
