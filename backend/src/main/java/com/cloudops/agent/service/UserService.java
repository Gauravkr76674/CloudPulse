package com.cloudops.agent.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.dto.CreateUserRequest;
import com.cloudops.agent.entity.Role;
import com.cloudops.agent.entity.User;
import com.cloudops.agent.exception.BadRequestException;
import com.cloudops.agent.exception.ConflictException;
import com.cloudops.agent.exception.NotFoundException;
import com.cloudops.agent.repository.UserRepository;
import com.cloudops.agent.security.CurrentUser;

/**
 * User administration. Passwords are BCrypt-hashed here and nowhere else;
 * no method on this class ever returns or logs a raw password.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAllByOrderByUsernameAsc();
    }

    @Transactional(readOnly = true)
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User '" + username + "' not found"));
    }

    @Transactional
    public User create(CreateUserRequest request) {
        User created = create(request.username(), request.email(), request.password(),
                request.fullName(), request.role());
        auditService.success("CREATE_USER", "User", created.getId(),
                "username=" + created.getUsername() + ", role=" + created.getRole());
        return created;
    }

    /**
     * Core creation path, also used by the bootstrap seeder at startup.
     */
    @Transactional
    public User create(String username, String email, String rawPassword, String fullName, Role role) {
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("Username '" + username + "' is already taken");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email '" + email + "' is already registered");
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new BadRequestException("Password must be at least 8 characters");
        }

        User user = new User(username, email, passwordEncoder.encode(rawPassword), fullName,
                role == null ? Role.VIEWER : role);
        User saved = userRepository.save(user);
        log.info("Created user '{}' with role {}", saved.getUsername(), saved.getRole());
        return saved;
    }

    @Transactional
    public User changeRole(Long id, Role role) {
        User user = findById(id);
        Role previous = user.getRole();

        if (previous == Role.ADMIN && role != Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new BadRequestException("Cannot demote the last remaining ADMIN");
        }

        user.setRole(role);
        User saved = userRepository.save(user);
        auditService.success("CHANGE_USER_ROLE", "User", id, previous + " -> " + role);
        return saved;
    }

    @Transactional
    public User setEnabled(Long id, boolean enabled) {
        User user = findById(id);

        if (!enabled && user.getUsername().equals(CurrentUser.username())) {
            throw new BadRequestException("You cannot disable your own account");
        }
        if (!enabled && user.getRole() == Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new BadRequestException("Cannot disable the last remaining ADMIN");
        }

        user.setEnabled(enabled);
        User saved = userRepository.save(user);
        auditService.success(enabled ? "ENABLE_USER" : "DISABLE_USER", "User", id,
                "username=" + user.getUsername());
        return saved;
    }

    @Transactional
    public void resetPassword(Long id, String newRawPassword) {
        if (newRawPassword == null || newRawPassword.length() < 8) {
            throw new BadRequestException("Password must be at least 8 characters");
        }
        User user = findById(id);
        user.setPasswordHash(passwordEncoder.encode(newRawPassword));
        userRepository.save(user);
        // Deliberately no password material in the audit details
        auditService.success("RESET_PASSWORD", "User", id, "username=" + user.getUsername());
    }

    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        User user = findByUsername(username);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            auditService.failure("CHANGE_OWN_PASSWORD", "User", user.getId(), "current password mismatch");
            throw new BadRequestException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new BadRequestException("New password must be at least 8 characters");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        auditService.success("CHANGE_OWN_PASSWORD", "User", user.getId(), "password updated");
    }

    @Transactional
    public void recordSuccessfulLogin(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            user.setLastLoginAt(Instant.now());
            userRepository.save(user);
        });
    }

    @Transactional(readOnly = true)
    public long countAdmins() {
        return userRepository.countByRole(Role.ADMIN);
    }
}
