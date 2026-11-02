package com.sahaj.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sahaj.platform.application.ClinicProfileInput;
import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;

/**
 * {@code ClinicProfile} in {@code docs/api/platform.yaml} (SAH-7), used for the request and the response.
 * {@code area} and {@code city} are the same stored value. {@code slug} and {@code specialtyCode} are
 * shown but never changed here: the slug is read-only and the specialty changes through
 * {@code PUT /clinic/specialty}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClinicProfileDto(String name, String nameLocal, String address, String area, String city, String pin,
                                String phone, String whatsapp, String mapsUrl, String registrationNo,
                                @JsonProperty(access = JsonProperty.Access.READ_ONLY) String slug,
                                String specialtyCode, String brand, String brandHex) {

    public static ClinicProfileDto from(ProfileRow row) {
        return new ClinicProfileDto(row.name(), row.nameLocal(), row.address(), row.city(), row.city(), row.pin(),
                row.phone(), row.whatsapp(), row.mapsUrl(), row.registrationNo(), row.slug(), row.specialtyCode(),
                row.brand(), row.brandHex());
    }

    public ClinicProfileInput toInput() {
        return new ClinicProfileInput(name, nameLocal, address, area, city, pin, phone, whatsapp, mapsUrl,
                registrationNo, brand, brandHex);
    }
}
