package com.cloudops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cloudops.agent.entity.ActionType;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.ResourceType;

/**
 * The catalog decides what may even be proposed for a given resource in its current state.
 * These tests pin the state rules, because they are what stops the agent from rebooting an
 * instance that has already stopped.
 */
class ActionCatalogTest {

    private ActionCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new ActionCatalog();
    }

    private CloudResource resource(ResourceType type, ResourceStatus status) {
        CloudResource resource = new CloudResource();
        resource.setId(1L);
        resource.setResourceId("i-0123456789abcdef0");
        resource.setName("demo-web-01");
        resource.setType(type);
        resource.setStatus(status);
        return resource;
    }

    @Test
    @DisplayName("a running instance may be rebooted, stopped or tagged")
    void runningInstanceAllowsMutations() {
        CloudResource running = resource(ResourceType.EC2_INSTANCE, ResourceStatus.RUNNING);

        assertThat(catalog.rejectionReason(ActionType.REBOOT_EC2_INSTANCE, running)).isEmpty();
        assertThat(catalog.rejectionReason(ActionType.STOP_EC2_INSTANCE, running)).isEmpty();
        assertThat(catalog.rejectionReason(ActionType.TAG_FOR_REVIEW, running)).isEmpty();
    }

    @Test
    @DisplayName("a running instance cannot be started again")
    void startIsPointlessWhenRunning() {
        CloudResource running = resource(ResourceType.EC2_INSTANCE, ResourceStatus.RUNNING);
        assertThat(catalog.rejectionReason(ActionType.START_EC2_INSTANCE, running))
                .isPresent()
                .get()
                .asString()
                .contains("already running");
    }

    @Test
    @DisplayName("a stopped instance cannot be rebooted or stopped")
    void stoppedInstanceRefusesRebootAndStop() {
        CloudResource stopped = resource(ResourceType.EC2_INSTANCE, ResourceStatus.STOPPED);

        assertThat(catalog.rejectionReason(ActionType.REBOOT_EC2_INSTANCE, stopped)).isPresent();
        assertThat(catalog.rejectionReason(ActionType.STOP_EC2_INSTANCE, stopped)).isPresent();
        assertThat(catalog.rejectionReason(ActionType.START_EC2_INSTANCE, stopped)).isEmpty();
    }

    @Test
    @DisplayName("nothing mutating is allowed on a terminated instance")
    void terminatedInstanceRefusesEverythingMutating() {
        CloudResource terminated = resource(ResourceType.EC2_INSTANCE, ResourceStatus.TERMINATED);

        for (ActionType type : ActionType.values()) {
            if (type.isMutating()) {
                assertThat(catalog.rejectionReason(type, terminated))
                        .as("%s must be refused on a terminated instance", type)
                        .isPresent();
            }
        }
    }

    @Test
    @DisplayName("advisory actions are always available")
    void advisoryActionsAlwaysAvailable() {
        CloudResource terminated = resource(ResourceType.EC2_INSTANCE, ResourceStatus.TERMINATED);

        assertThat(catalog.rejectionReason(ActionType.NOTIFY_ONCALL, terminated)).isEmpty();
        assertThat(catalog.rejectionReason(ActionType.NO_ACTION, terminated)).isEmpty();
        assertThat(catalog.rejectionReason(ActionType.COLLECT_MORE_METRICS, terminated)).isEmpty();
    }

    @Test
    @DisplayName("EC2 actions are refused on a non-EC2 resource")
    void ec2ActionsNeedAnEc2Resource() {
        CloudResource notAnInstance = resource(ResourceType.RDS_INSTANCE, ResourceStatus.RUNNING);
        assertThat(catalog.rejectionReason(ActionType.REBOOT_EC2_INSTANCE, notAnInstance)).isPresent();
    }

    @Test
    @DisplayName("a null action type and a null resource are both refused, not crashed on")
    void nullsAreRefused() {
        assertThat(catalog.rejectionReason(null,
                resource(ResourceType.EC2_INSTANCE, ResourceStatus.RUNNING))).isPresent();
        assertThat(catalog.rejectionReason(ActionType.REBOOT_EC2_INSTANCE, null)).isPresent();
    }

    @Test
    @DisplayName("the prompt is given a closed list of names, never free text")
    void allowedNamesAreEnumNames() {
        CloudResource running = resource(ResourceType.EC2_INSTANCE, ResourceStatus.RUNNING);
        List<String> names = catalog.allowedNamesFor(running);

        assertThat(names).isNotEmpty();
        for (String name : names) {
            assertThat(ActionType.parseSafely(name)).isPresent();
        }
        assertThat(names).doesNotContain("START_EC2_INSTANCE");
    }

    @Test
    @DisplayName("the catalog exposed to the UI covers every action")
    void catalogCoversEveryAction() {
        assertThat(catalog.catalog()).hasSize(ActionType.values().length);
        assertThat(catalog.isAutomatable(ActionType.RECOMMEND_MANUAL_SCALE_UP)).isFalse();
        assertThat(catalog.isAutomatable(ActionType.REBOOT_EC2_INSTANCE)).isTrue();
    }
}
