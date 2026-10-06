package com.cloudops.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.cloudops.agent.config.SecurityProperties;
import com.cloudops.agent.dto.LoginRequest;
import com.cloudops.agent.dto.LoginResponse;
import com.cloudops.agent.dto.UserDto;
import com.cloudops.agent.entity.AuditOutcome;
import com.cloudops.agent.entity.User;
import com.cloudops.agent.security.JwtService;
import com.cloudops.agent.util.DtoMapper;

/**
 * Login. Delegates credential checking to the {@link AuthenticationManager}
 * (DaoAuthenticationProvider + BCrypt) and issues a JWT on success.
 *
 * Both successful and failed attempts are audited; failures never reveal whether the
 * username existed.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;
    private final AuditService auditService;
    private final SecurityProperties securityProperties;

    public AuthService(AuthenticationManager authenticationManager, JwtService jwtService,
            UserService userService, AuditService auditService,
            SecurityProperties securityProperties) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userService = userService;
        this.auditService = auditService;
        this.securityProperties = securityProperties;
    }

    public LoginResponse login(LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));

            UserDetails principal = (UserDetails) authentication.getPrincipal();
            String token = jwtService.generateToken(principal);

            userService.recordSuccessfulLogin(principal.getUsername());
            User user = userService.findByUsername(principal.getUsername());

            auditService.recordAs(user.getUsername(), user.getRole(), "LOGIN", "User",
                    user.getId(), AuditOutcome.SUCCESS, "login successful");
            log.info("User '{}' logged in", user.getUsername());

            long expiresInSeconds = securityProperties.getJwtExpirationMinutes() * 60L;
            return LoginResponse.of(token, expiresInSeconds, DtoMapper.toUserDto(user));

        } catch (DisabledException ex) {
            auditService.recordAs(request.username(), null, "LOGIN", "User", null,
                    AuditOutcome.DENIED, "account disabled");
            throw new BadCredentialsException("Account is disabled");

        } catch (AuthenticationException ex) {
            // Do not leak whether the username exists
            auditService.recordAs(request.username(), null, "LOGIN", "User", null,
                    AuditOutcome.FAILURE, "invalid credentials");
            log.warn("Failed login attempt for username '{}'", request.username());
            throw new BadCredentialsException("Invalid username or password");
        }
    }

    /** GET /api/auth/me — the caller's own profile, resolved from the token. */
    public UserDto currentUser(String username) {
        return DtoMapper.toUserDto(userService.findByUsername(username));
    }
}
