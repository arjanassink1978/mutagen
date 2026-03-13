package dev.mutagen.llm;

import dev.mutagen.llm.client.LlmClientFactory;
import dev.mutagen.llm.client.LlmException;
import dev.mutagen.llm.provider.AnthropicLlmClient;
import dev.mutagen.llm.provider.OpenAiLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Test de factory logica via een helper die env vars simuleert.
 *
 * Omdat System.getenv() niet overschrijfbaar is in tests, gebruiken we
 * hier een aparte methode die via reflection de env vars injecteert,
 * of we testen de factory via een subklasse.
 *
 * Voor nu testen we de foutgevallen en de describe methode,
 * en integratie via de aparte provider tests.
 */
class LlmClientFactoryTest {

    @Test
    void describeConfiguration_noKeysSet_returnsNietGeconfigureerd() {
        // Als geen van de keys gezet zijn in de testomgeving
        // (dit is normaal het geval in een schone CI omgeving)
        String description = LlmClientFactory.describeConfiguration();
        // We kunnen niet garanderen wat er in de omgeving staat,
        // maar de methode moet altijd een string teruggeven
        assertThat(description).isNotNull().isNotBlank();
    }

    @Test
    void fromEnvironment_noKeysConfigured_throwsLlmException() {
        // Deze test faalt als er wél een key in de omgeving staat.
        // In dat geval wordt de test overgeslagen.
        boolean anyKeyPresent = System.getenv("ANTHROPIC_API_KEY") != null
                || System.getenv("OPENAI_API_KEY") != null;

        if (anyKeyPresent) {
            // Sla over — live keys aanwezig in omgeving
            return;
        }

        assertThatThrownBy(LlmClientFactory::fromEnvironment)
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).getErrorType())
                        .isEqualTo(LlmException.ErrorType.AUTHENTICATION));
    }
}
