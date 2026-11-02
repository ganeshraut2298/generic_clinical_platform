package com.sahaj.platform.application;

/**
 * Fields of {@code PUT /clinic/profile} (SAH-7). A null {@code area}/{@code city}, {@code pin},
 * {@code registrationNo} or {@code brand} keeps what is saved; a null in any other field clears it.
 * {@code area} and {@code city} are the same stored value (the city).
 */
public record ClinicProfileInput(String name, String nameLocal, String address, String area, String city,
                                  String pin, String phone, String whatsapp, String mapsUrl,
                                  String registrationNo, String brand, String brandHex) {
}
