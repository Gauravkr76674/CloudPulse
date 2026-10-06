package com.cloudops.agent.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.CreateUserRequest;
import com.cloudops.agent.dto.ResetPasswordRequest;
import com.cloudops.agent.dto.UserDto;
import com.cloudops.agent.entity.Role;
import com.cloudops.agent.service.UserService;
import com.cloudops.agent.util.DtoMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * User administration. Every endpoint here is ADMIN-only: creating accounts and changing roles
 * decides who is allowed to approve remediation, so it is the most privileged part of the API.
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Users", description = "Account administration (ADMIN only)")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserDto> list() {
        return DtoMapper.toUserDtos(userService.findAll());
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable Long id) {
        return DtoMapper.toUserDto(userService.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account with one of ADMIN, OPERATOR or VIEWER")
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        return DtoMapper.toUserDto(userService.create(request));
    }

    @PatchMapping("/{id}/role")
    @Operation(summary = "Change a user's role")
    public UserDto changeRole(@PathVariable Long id, @RequestParam Role role) {
        return DtoMapper.toUserDto(userService.changeRole(id, role));
    }

    @PatchMapping("/{id}/enabled")
    @Operation(summary = "Enable or disable an account without deleting it")
    public UserDto setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        return DtoMapper.toUserDto(userService.setEnabled(id, enabled));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id,
                                             @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
