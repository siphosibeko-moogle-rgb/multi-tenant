package com.example.inventory.users;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.users.UserDtos.User;
import com.example.inventory.users.UserDtos.UserInviteRequest;
import com.example.inventory.users.UserDtos.UserInviteResponse;
import com.example.inventory.users.UserDtos.UserPage;
import com.example.inventory.users.UserDtos.UserUpdateRequest;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/users")
public class UsersController {

    private final UserDirectory users;

    public UsersController(UserDirectory users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<UserPage> list(@RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String status) {
        return ResponseEntity.ok(users.list(cursor, limit, status));
    }

    @PostMapping
    @PreAuthorize("hasRole('owner')")
    ResponseEntity<UserInviteResponse> invite(@Valid @RequestBody UserInviteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(users.invite(request));
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasRole('owner')")
    ResponseEntity<User> update(@PathVariable UUID userId,
                                @Valid @RequestBody UserUpdateRequest request) {
        return users.update(userId, request)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("No such user"));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('owner')")
    ResponseEntity<Void> deactivate(@PathVariable UUID userId) {
        if (!users.deactivate(userId)) {
            throw new NotFoundException("No such user");
        }
        return ResponseEntity.noContent().build();
    }
}
