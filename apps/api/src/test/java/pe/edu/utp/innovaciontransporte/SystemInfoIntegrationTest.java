package pe.edu.utp.innovaciontransporte;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SystemInfoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void actuatorHealthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void systemInfoExposesFlywaySeedAndPropagatesCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/system/info").header("X-Correlation-Id", "ci-corr-001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "ci-corr-001"))
                .andExpect(jsonPath("$.corridor").value("Lima-Huancayo"))
                .andExpect(jsonPath("$.currency").value("PEN"))
                .andExpect(jsonPath("$.timezone").value("America/Lima"))
                .andExpect(jsonPath("$.delayThresholdMinutes").value(30))
                .andExpect(jsonPath("$.schemaInitialized").value(true))
                .andExpect(jsonPath("$.seededRouteSegments").value(6));
    }

    @Test
    void protectedEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());
    }
}
