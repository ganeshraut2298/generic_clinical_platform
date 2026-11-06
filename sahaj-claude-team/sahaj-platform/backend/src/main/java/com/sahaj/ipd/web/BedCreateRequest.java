package com.sahaj.ipd.web;

import java.util.UUID;

/** Body of POST /beds; roomId is optional. */
public record BedCreateRequest(UUID wardId, UUID roomId) {
}
