package org.example.observer;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 用 MVC 验证实验接口的路径绑定、JSON 字段及场景切换。 */
class DependencyEndpointTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ServiceObserverController()).build();

    @Test
    void dbSlowProvidesConnectionEvidence() throws Exception {
        mvc.perform(get("/api/v1/internal/day10/scenario/DB_SLOW")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/services/payment-service/dependencies")).andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceName").value("payment-service"))
                .andExpect(jsonPath("$.database.p99Ms").value(2500.0))
                .andExpect(jsonPath("$.database.activeConnections").value(49));
        mvc.perform(get("/api/v1/services/payment-service/status"))
                .andExpect(jsonPath("$.cpuPercent").value(45.0));
    }

    @Test
    void rpcTimeoutAndUnavailableHaveDifferentContracts() throws Exception {
        mvc.perform(get("/api/v1/internal/day10/scenario/RPC_TIMEOUT")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/services/payment-service/dependencies"))
                .andExpect(jsonPath("$.rpc.timeoutRate").value(0.35))
                .andExpect(jsonPath("$.rpc.p99Ms").value(3200.0));
        mvc.perform(get("/api/v1/internal/day10/scenario/UNAVAILABLE")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/services/payment-service/dependencies")).andExpect(status().isServiceUnavailable());
    }
}
