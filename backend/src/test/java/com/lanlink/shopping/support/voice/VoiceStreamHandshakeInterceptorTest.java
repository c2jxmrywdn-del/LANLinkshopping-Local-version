package com.lanlink.shopping.support.voice;

import com.lanlink.shopping.support.SupportRequestGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.socket.WebSocketHandler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceStreamHandshakeInterceptorTest {

    @Test
    void allowsHandshakeWhenSecretMatchesInHeader() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("support.proxy-secret", "secret-123");

        SupportRequestGuard guard = mock(SupportRequestGuard.class);
        when(guard.allowRequest(anyString())).thenReturn(true);

        VoiceStreamHandshakeInterceptor interceptor = new VoiceStreamHandshakeInterceptor(env, guard);

        ServerHttpRequest req = mock(ServerHttpRequest.class);
        ServerHttpResponse resp = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Support-Proxy-Secret", "secret-123");
        headers.set("X-Support-Client-IP", "127.0.0.1");

        when(req.getHeaders()).thenReturn(headers);
        when(req.getURI()).thenReturn(URI.create("http://localhost:8080/support/stream-voice?visitorToken=vis-1"));
        when(req.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));

        Map<String, Object> attrs = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), attrs);

        assertTrue(ok);
        assertEquals("127.0.0.1", attrs.get("clientIp"));
        assertEquals("vis-1", attrs.get("visitorToken"));
    }

    @Test
    void allowsHandshakeWhenSecretMatchesInQueryParam() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("support.proxy-secret", "secret-456");

        SupportRequestGuard guard = mock(SupportRequestGuard.class);
        when(guard.allowRequest(anyString())).thenReturn(true);

        VoiceStreamHandshakeInterceptor interceptor = new VoiceStreamHandshakeInterceptor(env, guard);

        ServerHttpRequest req = mock(ServerHttpRequest.class);
        ServerHttpResponse resp = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();

        when(req.getHeaders()).thenReturn(headers);
        when(req.getURI()).thenReturn(URI.create("http://localhost:8080/support/stream-voice?proxySecret=secret-456&visitorToken=vis-2"));
        when(req.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));

        Map<String, Object> attrs = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), attrs);

        assertTrue(ok);
        assertEquals("vis-2", attrs.get("visitorToken"));
    }

    @Test
    void rejectsHandshakeWhenSecretMismatch() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("support.proxy-secret", "correct-secret");

        SupportRequestGuard guard = mock(SupportRequestGuard.class);
        VoiceStreamHandshakeInterceptor interceptor = new VoiceStreamHandshakeInterceptor(env, guard);

        ServerHttpRequest req = mock(ServerHttpRequest.class);
        ServerHttpResponse resp = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Support-Proxy-Secret", "wrong-secret");

        when(req.getHeaders()).thenReturn(headers);
        when(req.getURI()).thenReturn(URI.create("http://localhost:8080/support/stream-voice"));
        when(req.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));

        Map<String, Object> attrs = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), attrs);

        assertFalse(ok);
        verify(resp).setStatusCode(HttpStatus.FORBIDDEN);
    }
}
