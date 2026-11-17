package com.lanlink.shopping.support;

import com.lanlink.shopping.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AiApiKeyServiceTest {

    @Test
    void createRejectsWildcardPermissions() {
        AiApiKeyService service = new AiApiKeyService(mock(JdbcTemplate.class), mock(AuditService.class));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create("legacy", "local", "*", null, 1L, null));
        assertTrue(error.getMessage().contains("通配"));
    }

    @Test
    void principalDoesNotTreatIntentScopeAsWildcard() {
        AiApiKeyService.Principal principal = new AiApiKeyService.Principal(
                "llk_readonly_****", Set.of("support:ask", "intent:analyze"), null);
        assertTrue(principal.has("support:ask"));
        assertTrue(principal.has("intent:analyze"));
    }
}
