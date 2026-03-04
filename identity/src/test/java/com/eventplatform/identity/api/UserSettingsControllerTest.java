package com.eventplatform.identity.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.identity.api.controller.UserSettingsController;
import com.eventplatform.identity.api.dto.response.PreferenceSelectionResponse;
import com.eventplatform.identity.api.dto.response.UserSettingsResponse;
import com.eventplatform.identity.service.AuthService;
import com.eventplatform.identity.service.PreferenceOptionsService;
import com.eventplatform.identity.service.UserSettingsService;
import com.eventplatform.identity.service.UserWalletService;
import com.eventplatform.shared.common.exception.ValidationException;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.SecurityConfig;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = com.eventplatform.identity.IdentityTestApplication.class,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
    }
)
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
class UserSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserSettingsService userSettingsService;
    @MockBean
    private AuthService authService;
    @MockBean
    private PreferenceOptionsService preferenceOptionsService;
    @MockBean
    private UserWalletService userWalletService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUpFilterPassThrough() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void should_return_200_on_settings_upsert_when_preferences_valid() throws Exception {
        UserSettingsResponse response = new UserSettingsResponse(
            "Name",
            "9999999999",
            LocalDate.of(2000, 1, 1),
            "Address",
            new PreferenceSelectionResponse(1L, "Pune"),
            List.of(new PreferenceSelectionResponse(2L, "Rock")),
            true
        );
        when(userSettingsService.upsertMySettings(any(Long.class), any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/users/me/settings")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{" +
                    "\"fullName\":\"Name\"," +
                    "\"phone\":\"9999999999\"," +
                    "\"dob\":\"2000-01-01\"," +
                    "\"address\":\"Address\"," +
                    "\"preferredCityOptionId\":1," +
                    "\"preferredGenreOptionIds\":[2]," +
                    "\"notificationOptIn\":true" +
                "}"))
            .andExpect(status().isOk());
    }

    @Test
    void should_return_400_on_settings_upsert_when_city_missing_or_genres_exceed_limit() throws Exception {
        when(userSettingsService.upsertMySettings(any(Long.class), any()))
            .thenThrow(new ValidationException("Invalid preference", "INVALID_PREFERENCE"));

        mockMvc.perform(put("/api/v1/users/me/settings")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{" +
                    "\"fullName\":\"Name\"," +
                    "\"phone\":\"9999999999\"," +
                    "\"address\":\"Address\"," +
                    "\"preferredGenreOptionIds\":[2,3,4,5]," +
                    "\"notificationOptIn\":true" +
                "}"))
            .andExpect(status().isBadRequest());
    }

    private UsernamePasswordAuthenticationToken userAuthentication() {
        return new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(1L, "USER", null),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
