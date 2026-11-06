package com.sahaj.ipd.web;

/** Body of PUT /charges. */
public record ChargesRequest(String bedDayCut, Long nursingPaise, Long visitPaise) {
}
