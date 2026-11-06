package com.sahaj.ipd.web;

import java.math.BigDecimal;

/** Body of PUT /categories/{key}. */
public record CategoryChargeRequest(Long ratePaise, BigDecimal multiplier) {
}
