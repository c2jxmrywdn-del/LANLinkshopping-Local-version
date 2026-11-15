package com.lanlink.shopping.social;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.SocialLoginProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppleAuthHandoffTest {

    private AppleAuthHandoff handoff;

    @BeforeEach
    void setUp() {
        SocialLoginProperties props = new SocialLoginProperties();
        props.setRedirectBase("http://localhost:5173/login");
        handoff = new AppleAuthHandoff(props);
    }

    @Test
    void relayKeepsNameOnServerAndReturnsCodeToFrontend() {
        String state = "a".repeat(32);
        String nonce = "b".repeat(32);
        handoff.remember(state, nonce);

        String first = handoff.relay("auth.code", state,
                "{\"name\":{\"firstName\":\"俊\",\"lastName\":\"杰\"},\"email\":\"hide@privaterelay.appleid.com\"}", null);
        String second = handoff.relay("auth.code", state,
                "{\"name\":{\"firstName\":\"Other\",\"lastName\":\"Name\"}}", null);

        assertTrue(first.startsWith("http://localhost:5173/login?"));
        assertTrue(first.contains("code=auth.code"));
        assertTrue(first.contains("state=" + state));
        assertFalse(first.contains("privaterelay"));
        assertFalse(first.contains("%E4%BF%8A"));
        assertEquals(first, second);

        AppleAuthHandoff.Claim claim = handoff.consume(state);
        assertEquals(nonce, claim.nonce());
        assertEquals("俊 杰", claim.displayName());
        assertNull(handoff.consume(state));
    }

    @Test
    void unknownStateAndUnsafeErrorDoNotLeaveTheLoginPage() {
        String missing = handoff.relay("code", "c".repeat(32), null, null);
        assertTrue(missing.contains("error=apple_state"));
        assertFalse(missing.contains("code="));

        handoff.remember("d".repeat(32), "e".repeat(32));
        String cancelled = handoff.relay(null, "d".repeat(32), null, "user_cancelled_authorize");
        assertTrue(cancelled.contains("error=user_cancelled_authorize"));

        String injected = handoff.relay(null, "d".repeat(32), null, "https://evil.example");
        assertTrue(injected.contains("error=apple_denied"));
        assertFalse(injected.contains("evil.example"));
    }

    @Test
    void rejectsShortStateAndIllegalRedirectBase() {
        assertThrows(BusinessException.class, () -> handoff.remember("short", "f".repeat(32)));

        SocialLoginProperties props = new SocialLoginProperties();
        props.setRedirectBase("javascript:alert(1)");
        AppleAuthHandoff bad = new AppleAuthHandoff(props);
        bad.remember("a".repeat(32), "b".repeat(32));
        assertThrows(BusinessException.class, () -> bad.relay("code", "a".repeat(32), null, null));
    }
}
