package com.sahaj.platform.web;

import com.sahaj.platform.application.ColleagueTriesNoticeView;
import java.time.Instant;

/**
 * {@code ColleagueTriesNotice} in {@code docs/api/platform.yaml} (SAH-38). Every field is always present (null where the
 * contract allows it). Counts and state only: no name, no PIN.
 */
public record ColleagueTriesNoticeResponse(int unseenCount, Instant latestAt, String state, Instant lockedUntil,
                                           String notSetReason) {

    public static ColleagueTriesNoticeResponse from(ColleagueTriesNoticeView view) {
        return new ColleagueTriesNoticeResponse(view.unseenCount(), view.latestAt(), view.state().name(),
                view.lockedUntil(), view.notSetReason());
    }
}
