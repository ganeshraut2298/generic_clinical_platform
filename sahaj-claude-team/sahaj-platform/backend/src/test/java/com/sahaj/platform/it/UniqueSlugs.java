package com.sahaj.platform.it;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Web addresses (slugs) for the test clinics. The old helpers used {@code "sahaj-"} plus the first 8 hex characters of a random
 * UUID: 32 bits of luck against a {@code sahaj_test} database that is never cleaned and holds every clinic ever created by every
 * gate run. With tens of thousands of old rows each new clinic had a chance in about a hundred thousand of meeting one, which
 * adds up over the 1,500 or more clinics of one gate run (SlugTakenException in SeniorCallIT and PackageTableIT, once each).
 *
 * <p>A slug here is guaranteed unique inside one JVM (millisecond clock plus a counter) and carries 24 random bits on top for
 * other JVMs at the same millisecond. The setup rule is 3 to 24 lowercase letters, digits or hyphens, so the shape is
 * {@code sahaj-} + 8 (time, base 36) + 2 (counter, base 36) + 6 (random hex) = 22 characters, so that the "-2" alternatives the setup offers for a taken address (24 at most) still fit (SetupAndShellIT asserts that a taken address gets alternatives; a 24-character slug left none). The {@code sahaj-} prefix is kept
 * because {@code ClinicMastersIT} asserts it.
 */
final class UniqueSlugs {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private UniqueSlugs() {
    }

    static String next() {
        String time = Long.toString(System.currentTimeMillis(), 36);
        String count = Integer.toString(1296 + SEQUENCE.getAndIncrement() % 1296, 36).substring(1); // always 2 characters
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return "sahaj-" + time + count + random;
    }
}
