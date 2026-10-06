package com.cloudops.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.cloudops.agent.aws.CloudProvider;
import com.cloudops.agent.dto.SyncResultDto;
import com.cloudops.agent.entity.Role;
import com.cloudops.agent.repository.CloudResourceRepository;
import com.cloudops.agent.repository.UserRepository;
import com.cloudops.agent.service.CloudResourceService;
import com.cloudops.agent.service.UserService;

/**
 * Creates the first ADMIN account at startup so the API is usable on a fresh database,
 * and — only when the mock provider is active — imports its instances so the dashboard
 * has something to show on the very first page load.
 *
 * Credentials come from the environment (BOOTSTRAP_ADMIN_USERNAME / BOOTSTRAP_ADMIN_PASSWORD);
 * nothing is hardcoded here and the password is never logged. Runs only when no ADMIN
 * exists, so restarts never overwrite a real account.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserService userService;
    private final UserRepository userRepository;
    private final SecurityProperties securityProperties;
    private final CloudProperties cloudProperties;
    private final CloudProvider cloudProvider;
    private final CloudResourceService resourceService;
    private final CloudResourceRepository resourceRepository;

    public DataSeeder(UserService userService, UserRepository userRepository,
            SecurityProperties securityProperties, CloudProperties cloudProperties,
            CloudProvider cloudProvider, CloudResourceService resourceService,
            CloudResourceRepository resourceRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.securityProperties = securityProperties;
        this.cloudProperties = cloudProperties;
        this.cloudProvider = cloudProvider;
        this.resourceService = resourceService;
        this.resourceRepository = resourceRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedAdmin();
        if (securityProperties.isSeedDemoUsers()) {
            seedDemoUsers();
        }
        seedMockResources();
    }

    private void seedAdmin() {
        if (userService.countAdmins() > 0) {
            log.debug("An ADMIN account already exists; skipping bootstrap");
            return;
        }

        String username = securityProperties.getBootstrapAdminUsername();
        String password = securityProperties.getBootstrapAdminPassword();

        if (password == null || password.isBlank()) {
            log.error("No ADMIN account exists and BOOTSTRAP_ADMIN_PASSWORD is not set. "
                    + "Set it and restart, otherwise nobody can log in.");
            return;
        }

        try {
            userService.create(username, securityProperties.getBootstrapAdminEmail(), password,
                    "Bootstrap Administrator", Role.ADMIN);
            log.warn("Bootstrap ADMIN '{}' created. Change this password after first login.", username);
        } catch (Exception ex) {
            log.error("Could not create the bootstrap ADMIN: {}", ex.getMessage());
        }
    }

    /**
     * Demo OPERATOR and VIEWER accounts for the walkthrough. They reuse the bootstrap
     * password so no second secret has to be managed; disable with SEED_DEMO_USERS=false.
     */
    private void seedDemoUsers() {
        String password = securityProperties.getBootstrapAdminPassword();
        if (password == null || password.isBlank()) {
            return;
        }

        createIfMissing("operator", "operator@cloudops.local", password, "Demo Operator", Role.OPERATOR);
        createIfMissing("viewer", "viewer@cloudops.local", password, "Demo Viewer", Role.VIEWER);
    }

    /**
     * Imports the mock provider's instances on a fresh database so the dashboard has data on
     * the first page load instead of showing zeros until somebody clicks "Sync resources".
     *
     * Three deliberate guards:
     * <ul>
     *   <li>It does nothing when a real provider is active. Auto-importing somebody's actual
     *       AWS account at startup is not a decision a seeder gets to make — with
     *       CLOUD_PROVIDER=aws, sync stays an explicit, audited, operator-triggered call.</li>
     *   <li>It does nothing when resources already exist, so restarts never disturb a
     *       database that has been worked in.</li>
     *   <li>Failure is logged and swallowed. A demo convenience must never stop the
     *       application from starting; the endpoint is still there to retry by hand.</li>
     * </ul>
     *
     * {@link CloudResourceService#sync()} does the real work, so the seeded rows go through the
     * same path, the same MOCK provenance stamp and the same RESOURCE_SYNC audit entry as a
     * manual sync — there is no second, divergent write path to keep in step.
     */
    private void seedMockResources() {
        if (!cloudProperties.isSeedMockResources()) {
            log.debug("Mock resource seeding is disabled");
            return;
        }
        if (cloudProvider.real()) {
            log.debug("Provider '{}' is live; leaving resource discovery to an explicit sync",
                    cloudProvider.providerName());
            return;
        }
        long existing = resourceRepository.count();
        if (existing > 0) {
            log.debug("{} resources already present; skipping mock seed", existing);
            return;
        }

        try {
            SyncResultDto result = resourceService.sync();
            log.info("Seeded {} mock resources from provider '{}' ({} discovered)",
                    result.created(), result.provider(), result.discovered());
        } catch (Exception ex) {
            // Never fail startup for a demo convenience: POST /api/resources/sync still works.
            log.warn("Could not seed mock resources: {}", ex.getMessage());
        }
    }

    private void createIfMissing(String username, String email, String password, String fullName,
            Role role) {
        if (userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
            return;
        }
        try {
            userService.create(username, email, password, fullName, role);
            log.info("Seeded demo user '{}' with role {}", username, role);
        } catch (Exception ex) {
            log.warn("Could not seed demo user '{}': {}", username, ex.getMessage());
        }
    }
}
