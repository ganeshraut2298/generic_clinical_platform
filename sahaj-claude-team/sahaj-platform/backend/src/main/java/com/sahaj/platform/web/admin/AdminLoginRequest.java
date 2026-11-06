package com.sahaj.platform.web.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Contract schema LoginInput. The password is never printed. */
public record AdminLoginRequest(
        @NotNull(message = "Enter a valid work email")
        @Size(min = 3, max = 254, message = "Enter a valid work email")
        @Pattern(regexp = "^\\s*[^@\\s]+@[^@\\s]+\\.[^@\\s]+\\s*$", message = "Enter a valid work email")
        String email,
        @NotNull(message = "Enter your password")
        @Size(min = 1, max = 128, message = "Enter your password")
        String password) {

    @Override
    public String toString() {
        return "AdminLoginRequest[]";
    }
}
