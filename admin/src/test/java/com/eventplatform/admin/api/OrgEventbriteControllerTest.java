package com.eventplatform.admin.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.admin.api.controller.OrgEventbriteController;
import com.eventplatform.admin.service.OrgDashboardService;
import com.eventplatform.admin.service.OrgOAuthService;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.common.exception.ValidationException;
import com.eventplatform.shared.eventbrite.exception.EbAuthException;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.JwtTokenProvider;
import com.eventplatform.shared.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ContextConfiguration;

import static org.mockito.Mockito.mock;

@WebMvcTest(OrgEventbriteController.class)
@ContextConfiguration(classes = com.eventplatform.admin.AdminTestApplication.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, OrgEventbriteControllerTest.MockBeans.class})
class OrgEventbriteControllerTest {

    @TestConfiguration
    static class MockBeans {
        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return mock(JwtTokenProvider.class);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
            return new JwtAuthenticationFilter(jwtTokenProvider);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrgOAuthService orgOAuthService;
    @MockBean
    private OrgDashboardService orgDashboardService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void should_return_200_with_authorization_url_when_connect_initiated() throws Exception {
        when(orgOAuthService.buildAuthorizationUrl(1L)).thenReturn("http://auth?state=abc");

        mockMvc.perform(post("/api/v1/admin/orgs/1/eventbrite/connect"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorizationUrl").value("http://auth?state=abc"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void should_return_403_when_user_not_ADMIN() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orgs/1/eventbrite/connect"))
            .andExpect(status().isForbidden());
    }

    @Test
    void should_return_400_when_oauth_state_invalid() throws Exception {
        when(orgOAuthService.handleCallback(eq(1L), eq("code"), eq("bad")))
            .thenThrow(new ValidationException("bad", "OAUTH_STATE_INVALID"));

        mockMvc.perform(get("/api/v1/admin/orgs/1/eventbrite/callback")
                .queryParam("code", "code")
                .queryParam("state", "bad"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_502_when_code_exchange_fails() throws Exception {
        when(orgOAuthService.handleCallback(eq(1L), eq("code"), eq("state")))
            .thenThrow(new EbAuthException("boom"));

        mockMvc.perform(get("/api/v1/admin/orgs/1/eventbrite/callback")
                .queryParam("code", "code")
                .queryParam("state", "state"))
            .andExpect(status().isBadGateway());
    }
}
