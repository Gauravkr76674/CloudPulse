package com.cloudops.agent.security;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cloudops.agent.entity.User;
import com.cloudops.agent.repository.UserRepository;

/**
 * Loads a {@link User} from the database and adapts it into a Spring Security
 * {@link UserDetails}. Username lookup is case-sensitive, matching the DB unique index.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No user found with username: " + username));

        // Role.authority() returns "ROLE_ADMIN", "ROLE_OPERATOR", or "ROLE_VIEWER"
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(user.getRole().authority());

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPasswordHash(),
                user.isEnabled(),
                /* accountNonExpired */ true,
                /* credentialsNonExpired */ true,
                /* accountNonLocked */ true,
                List.of(authority));
    }
}
