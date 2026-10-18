package com.example.inventory.users;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.inventory.auth.AuthDtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    public static final List<String> ROLES = List.of("owner", "manager", "clerk", "viewer");

    public record User(
            UUID id,
            String email,
            String fullName,
            String role,
            String status,
            OffsetDateTime lastLoginAt) {
    }

    public record UserInviteRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Pattern(regexp = "owner|manager|clerk|viewer",
                    message = "must be one of owner, manager, clerk, viewer")
            String role) {
    }
    /** Returned once, by POST /users. The temporary password is never stored in plain text. */
    public record UserInviteResponse(User user, String temporaryPassword) {

        @Override
        public String toString() {
            return "UserInviteResponse[user=" + user + ", temporaryPassword=redacted]";
        }
    }

    public record UserUpdateRequest(
            @Pattern(regexp = "owner|manager|clerk|viewer",
                    message = "must be one of owner, manager, clerk, viewer")
            String role,
            @Pattern(regexp = "active|disabled", message = "must be active or disabled")
            String status,
            @Size(max = 200) String fullName) {
    }

    public record UserPage(List<User> items, String nextCursor) {
    }
}
