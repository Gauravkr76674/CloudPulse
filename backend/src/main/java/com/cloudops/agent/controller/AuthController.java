package com.cloudops.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.ChangePasswordRequest;
import com.cloudops.agent.dto.LoginRequest;
import com.cloudops.agent.dto.LoginResponse;
import com.cloudops.agent.dto.UserDto;
import com.cloudops.agent.security.CurrentUser;
import com.cloudops.agent.service.AuthService;
import com.cloudops.agent.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Login and the caller's own account. {@code /api/auth/login} is the only endpoint in the
 * application that does not require a token.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Login and self-service account endpoints")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange username and password for a JWT")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    @Operation(summary = "The account behind the current token")
    public UserDto me() {
        return authService.currentUser(CurrentUser.username());
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change your own password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changeOwnPassword(CurrentUser.username(),
                request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
