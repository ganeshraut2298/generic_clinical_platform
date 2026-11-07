package com.sahaj.platform.application;

import com.sahaj.platform.domain.PinState;
import java.time.Instant;

/**
 * {@code ColleagueTriesNotice} in {@code docs/api/platform.yaml} (SAH-38, ADR-014 D8): the signed-in person's own
 * count of wrong tries of their PIN by colleagues that they have not dismissed, and the PIN state. Counts and state
 * only: never who tried, never a try's time other than the newest ({@code latestAt}, sent back as {@code seenThrough}).
 */
public record ColleagueTriesNoticeView(int unseenCount, Instant latestAt, PinState state, Instant lockedUntil,
                                       String notSetReason) {
}
