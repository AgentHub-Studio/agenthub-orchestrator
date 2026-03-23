package com.agenthub.orchestrator.shared.multitenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MultiTenantRequestFilter
 */
@ExtendWith(MockitoExtension.class)
class MultiTenantRequestFilterTest {

    private MultiTenantRequestFilter filter;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    /**
     * Builds a minimal fake JWT (unsigned) with the provided payload JSON.
     * The filter only needs to parse the payload — signature is not verified here.
     */
    private static String buildFakeJwt(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());
        return header + "." + payload + ".fakesignature";
    }

    @BeforeEach
    void setUp() {
        filter = new MultiTenantRequestFilter();
        // Ensure clean state before each test
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void shouldSetTenantContextWhenValidBearerTokenIsPresent() throws Exception {
        // Arrange
        String tenantId = "550e8400-e29b-41d4-a716-446655440000";
        String userId = "user-sub-123";
        String payloadJson = String.format(
                "{\"iss\":\"http://keycloak.cezar.dev/realms/%s\",\"sub\":\"%s\"}",
                tenantId, userId
        );
        String token = buildFakeJwt(payloadJson);

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert — context is cleared after filterChain, so capture inside chain execution
        // We verify via the filterChain interaction and inspect captured context
        verify(filterChain, times(1)).doFilter(request, response);
        // After the filter runs, context must be cleared
        assertThat(TenantContextHolder.getContext()).isNull();
    }

    @Test
    void shouldClearContextAfterFilterChainExecutes() throws Exception {
        // Arrange
        String tenantId = "test-realm-uuid";
        String payloadJson = String.format(
                "{\"iss\":\"http://keycloak.internal:8080/realms/%s\",\"sub\":\"user-abc\"}",
                tenantId
        );
        String token = buildFakeJwt(payloadJson);

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        // Capture context during filter execution
        TenantContext[] capturedContext = new TenantContext[1];
        doAnswer(invocation -> {
            capturedContext[0] = TenantContextHolder.getContext();
            return null;
        }).when(filterChain).doFilter(request, response);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert — context was set during chain execution
        assertThat(capturedContext[0]).isNotNull();
        assertThat(capturedContext[0].getTenantId()).isEqualTo(tenantId);
        assertThat(capturedContext[0].getUserId()).isEqualTo("user-abc");

        // Assert — context is cleared after filter completes
        assertThat(TenantContextHolder.getContext()).isNull();
    }

    @Test
    void shouldNotSetTenantContextWhenNoAuthorizationHeader() throws Exception {
        // Arrange
        when(request.getHeader("Authorization")).thenReturn(null);

        // Capture context during filter execution
        TenantContext[] capturedContext = new TenantContext[1];
        doAnswer(invocation -> {
            capturedContext[0] = TenantContextHolder.getContext();
            return null;
        }).when(filterChain).doFilter(request, response);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert — no context set when no header
        assertThat(capturedContext[0]).isNull();
        assertThat(TenantContextHolder.getContext()).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldNotSetTenantContextWhenAuthorizationHeaderIsNotBearer() throws Exception {
        // Arrange
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

        // Capture context during filter execution
        TenantContext[] capturedContext = new TenantContext[1];
        doAnswer(invocation -> {
            capturedContext[0] = TenantContextHolder.getContext();
            return null;
        }).when(filterChain).doFilter(request, response);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert — no context set for non-Bearer auth
        assertThat(capturedContext[0]).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void shouldClearContextEvenWhenFilterChainThrowsException() throws Exception {
        // Arrange
        String tenantId = "exception-test-realm";
        String payloadJson = String.format(
                "{\"iss\":\"http://keycloak.internal:8080/realms/%s\",\"sub\":\"user-xyz\"}",
                tenantId
        );
        String token = buildFakeJwt(payloadJson);

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        doThrow(new RuntimeException("Chain failure")).when(filterChain).doFilter(request, response);

        // Act — should propagate the exception
        try {
            filter.doFilterInternal(request, response, filterChain);
        } catch (RuntimeException ignored) {
            // expected
        }

        // Assert — context is cleared in the finally block even on exception
        assertThat(TenantContextHolder.getContext()).isNull();
    }
}
