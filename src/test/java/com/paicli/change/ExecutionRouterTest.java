package com.paicli.change;

import com.paicli.config.PaiCliConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionRouterTest {
    @Test
    void mapsRiskToConfiguredProviderModelAndPolicies() {
        PaiCliConfig config = new PaiCliConfig();
        PaiCliConfig.PaiChangeRouteConfig medium = new PaiCliConfig.PaiChangeRouteConfig();
        medium.setProvider("glm");
        medium.setModel("glm-route-medium");
        medium.setRepairEnabled(false);
        medium.setDeliveryApprovalRequired(true);
        medium.setToolPolicy("locked_down");
        config.getPaiChange().getRoutes().put("MEDIUM", medium);

        ExecutionRoute route = ExecutionRouter.fromConfig(config)
                .route(new RiskAssessment(RiskLevel.MEDIUM, 3, List.of("sensitive_area:+3")));

        assertEquals(RiskLevel.MEDIUM, route.riskLevel());
        assertEquals("glm", route.provider());
        assertEquals("glm-route-medium", route.model());
        assertEquals(ExecutionRoute.ExecutionMode.REACT, route.executionMode());
        assertFalse(route.repairEnabled());
        assertTrue(route.specApprovalRequired());
        assertTrue(route.deliveryApprovalRequired());
        assertEquals(ExecutionRoute.ToolPolicyProfile.LOCKED_DOWN, route.toolPolicy());
    }

    @Test
    void highRiskDefaultsDisableRepairAndUseLockedDownTools() {
        ExecutionRoute route = ExecutionRouter.fromConfig(new PaiCliConfig())
                .route(new RiskAssessment(RiskLevel.HIGH, 7, List.of()));

        assertEquals("deepseek", route.provider());
        assertEquals("DeepSeek-V4-pro", route.model());
        assertFalse(route.repairEnabled());
        assertEquals(ExecutionRoute.ToolPolicyProfile.LOCKED_DOWN, route.toolPolicy());
    }

    @Test
    void highRiskDeliveryApprovalCannotBeDisabledByRouteConfiguration() {
        PaiCliConfig config = new PaiCliConfig();
        PaiCliConfig.PaiChangeRouteConfig high = new PaiCliConfig.PaiChangeRouteConfig();
        high.setProvider("deepseek");
        high.setModel("high-model");
        high.setDeliveryApprovalRequired(false);
        config.getPaiChange().getRoutes().put("HIGH", high);

        ExecutionRoute route = ExecutionRouter.fromConfig(config)
                .route(new RiskAssessment(RiskLevel.HIGH, 0, List.of("work_item.label.high_risk")));

        assertTrue(route.deliveryApprovalRequired());
    }
}
