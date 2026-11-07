package com.sahaj.platform.web;

/**
 * {@code ColleagueTriesSeenInput} in {@code docs/api/platform.yaml} (SAH-38). No bean validation here on purpose: the
 * service answers 400 field {@code seenThrough} "Reload the page and try again." for a missing or unreadable value.
 */
public record ColleagueTriesSeenRequest(String seenThrough) {
}
