package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.service.CustomUserDetailsService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedListRequestReceivesNumericUserAndOrganization() throws Exception {
        JwtUtils jwt = mock(JwtUtils.class);
        CustomUserDetailsService users = mock(CustomUserDetailsService.class);
        when(jwt.validateToken("valid")).thenReturn(true);
        when(jwt.extractUsernameFromToken("valid")).thenReturn("alice");
        when(jwt.extractUserIdFromToken("valid")).thenReturn("42");
        when(jwt.extractOrgTagsFromToken("valid")).thenReturn("team");
        when(jwt.extractRoleFromToken("valid")).thenReturn("USER");
        when(users.loadUserByUsername("alice")).thenReturn(User.withUsername("alice").password("unused").roles("USER").build());
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "jwtUtils", jwt);
        ReflectionTestUtils.setField(filter, "userDetailsService", users);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/documents/accessible");
        request.addHeader("Authorization", "Bearer valid");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertEquals("42", req.getAttribute("userId"));
            assertEquals("team", req.getAttribute("orgTags"));
            assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
        });
    }

    @Test
    void invalidTokenDoesNotPopulateUserAttributes() throws Exception {
        JwtUtils jwt = mock(JwtUtils.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "jwtUtils", jwt);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/documents/accessible");
        request.addHeader("Authorization", "Bearer invalid");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertNull(req.getAttribute("userId"));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        });
        verify(jwt, never()).extractUserIdFromToken("invalid");
    }
}
