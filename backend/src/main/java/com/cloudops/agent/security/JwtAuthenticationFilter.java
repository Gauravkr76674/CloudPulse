package com.cloudops.agent.security;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads the "Authorization: Bearer &lt;token&gt;" header, validates the token and puts
 * an {@link org.springframework.security.core.Authentication} into the SecurityContext.
 *
 * A bad or expired token is not an error here: the context simply stays empty and
 * {@link RestAuthenticationEntryPoint} turns that into a 401 if the endpoint is protected.
 * OncePerRequestFilter guarantees it runs a single time per request even with forwards.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(PREFIX.length()).trim();

        // Never re-authenticate a request that already carries an authentication
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String username = jwtService.extractUsername(token);
            String role = jwtService.extractRole(token);

            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            if (jwtService.isTokenValid(token, userDetails) && userDetails.isEnabled()) {
                // Prefer the authorities from the database over the token claim, so a
                // role change or a disabled account takes effect immediately.
                List<SimpleGrantedAuthority> authorities = userDetails.getAuthorities().isEmpty()
                        ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        : userDetails.getAuthorities().stream()
                                .map(a -> new SimpleGrantedAuthority(a.getAuthority()))
                                .toList();

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (JwtException | IllegalArgumentException ex) {
            // Expired / tampered / malformed token — log the reason, not the token itself
            log.debug("Rejected JWT for {} {}: {}", request.getMethod(), request.getRequestURI(),
                    ex.getMessage());
            SecurityContextHolder.clearContext();
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException ex) {
            log.debug("JWT referenced a user that no longer exists");
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
