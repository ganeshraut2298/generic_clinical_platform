package com.sahaj.platform.application;

import com.sahaj.platform.domain.AppUser;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.app_user} (no row level security: global, reached only through SAH-3). */
public interface AppUserRepository {

    Optional<AppUser> findByMobile(String mobile);

    Optional<AppUser> findById(UUID userId);

    /** Inserts a new person with the given id (chosen by the caller, see {@code AuthenticationScope}). */
    void insert(UUID id, String mobile, String fullName);
}
