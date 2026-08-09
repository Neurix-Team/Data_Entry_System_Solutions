package com.dataentry.controller;

import com.dataentry.dto.UserDtos;
import com.dataentry.model.User;
import com.dataentry.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserDtos.UserResponse> list() {
        return userService.list();
    }

    @PostMapping
    public ResponseEntity<UserDtos.UserResponse> create(@Valid @RequestBody UserDtos.CreateUserRequest req) {
        return ResponseEntity.ok(userService.create(req));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserDtos.UserResponse> update(@PathVariable Long id,
                                                       @Valid @RequestBody UserDtos.UpdateUserRequest req) {
        return ResponseEntity.ok(userService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal User current) {
        userService.delete(id, current.getId());
        return ResponseEntity.noContent().build();
    }
}
