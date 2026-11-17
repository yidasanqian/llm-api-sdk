package example;

import io.github.yidasanqian.llm.client.LlmClient;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneExampleTest {
    @Test void consumesInstalledArtifactsWithoutSpringContextOrInfrastructure() {
        assertEquals(0, new BigDecimal("0.0026").compareTo(StandaloneExample.quoteFixture().total()));
        assertEquals(0, new BigDecimal("7.623").compareTo(StandaloneExample.quoteSeedanceFixture().total()));
        assertTrue(StandaloneExample.bridgeFixture().containsKey("input"));
        try (var client = LlmClient.builder().build()) {
            assertNotNull(client);
        }
    }
}
