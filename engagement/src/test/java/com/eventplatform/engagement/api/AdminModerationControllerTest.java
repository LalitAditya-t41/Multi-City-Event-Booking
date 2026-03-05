package com.eventplatform.engagement.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.engagement.api.controller.AdminModerationController;
import com.eventplatform.engagement.api.dto.response.AdminReviewResponse;
import com.eventplatform.engagement.api.dto.response.ModerationResponse;
import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import com.eventplatform.engagement.exception.ReviewAlreadyModeratedException;
import com.eventplatform.engagement.exception.ReviewNotFoundException;
import com.eventplatform.engagement.service.ModerationService;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminModerationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminModerationControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ModerationService moderationService;
  @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

  @BeforeEach
  void setUpFilterPassThrough() throws Exception {
    doAnswer(
            invocation -> {
              jakarta.servlet.FilterChain chain = invocation.getArgument(2);
              chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(jwtAuthenticationFilter)
        .doFilter(any(), any(), any());
  }

  @Test
  void should_return_200_for_admin_queue_when_role_admin() throws Exception {
    when(moderationService.listAdminQueue(any(), any(), any(), any()))
        .thenReturn(
            new PageImpl<>(
                List.of(
                    new AdminReviewResponse(
                        56L,
                        1001L,
                        200L,
                        2,
                        "Bad",
                        "text",
                        ReviewStatus.PENDING_MODERATION,
                        AttendanceVerificationStatus.EB_VERIFIED,
                        Instant.parse("2026-03-05T16:00:00Z"),
                        1,
                        null)),
                PageRequest.of(0, 20),
                1));

    mockMvc
        .perform(
            get("/api/v1/admin/engagement/reviews").with(authentication(adminAuthentication())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].reviewId").value(56));
  }

  @Test
  void should_return_403_for_admin_queue_when_role_user() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/engagement/reviews").with(authentication(userAuthentication())))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_return_200_when_moderate_approve_successful() throws Exception {
    when(moderationService.applyManualDecision(any(), any(), any(), any()))
        .thenReturn(
            new ModerationResponse(
                56L, ReviewStatus.PUBLISHED, Instant.parse("2026-03-05T18:10:00Z")));

    mockMvc
        .perform(
            put("/api/v1/admin/engagement/reviews/56/moderate")
                .with(authentication(adminAuthentication()))
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.newStatus").value("PUBLISHED"));
  }

  @Test
  void should_return_200_when_moderate_reject_successful() throws Exception {
    when(moderationService.applyManualDecision(any(), any(), any(), any()))
        .thenReturn(
            new ModerationResponse(
                56L, ReviewStatus.REJECTED, Instant.parse("2026-03-05T18:10:00Z")));

    mockMvc
        .perform(
            put("/api/v1/admin/engagement/reviews/56/moderate")
                .with(authentication(adminAuthentication()))
                .contentType("application/json")
                .content("{\"decision\":\"REJECT\",\"reason\":\"policy\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.newStatus").value("REJECTED"));
  }

  @Test
  void should_return_400_when_moderate_reject_missing_reason() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/engagement/reviews/56/moderate")
                .with(authentication(adminAuthentication()))
                .contentType("application/json")
                .content("{\"decision\":\"REJECT\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

    verify(moderationService, never()).applyManualDecision(any(), any(), any(), any());
  }

  @Test
  void should_return_409_when_moderate_review_already_moderated() throws Exception {
    when(moderationService.applyManualDecision(any(), any(), any(), any()))
        .thenThrow(new ReviewAlreadyModeratedException());

    mockMvc
        .perform(
            put("/api/v1/admin/engagement/reviews/56/moderate")
                .with(authentication(adminAuthentication()))
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("REVIEW_ALREADY_MODERATED"));
  }

  @Test
  void should_return_404_when_moderate_review_not_found() throws Exception {
    when(moderationService.applyManualDecision(any(), any(), any(), any()))
        .thenThrow(new ReviewNotFoundException(56L));

    mockMvc
        .perform(
            put("/api/v1/admin/engagement/reviews/56/moderate")
                .with(authentication(adminAuthentication()))
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_FOUND"));
  }

  @Test
  void should_return_403_when_moderate_review_called_without_admin_role() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/engagement/reviews/56/moderate")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isForbidden());
  }

  private UsernamePasswordAuthenticationToken adminAuthentication() {
    return new UsernamePasswordAuthenticationToken(
        new AuthenticatedUser(1L, "ADMIN", 1L, "admin@test.com"),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  private UsernamePasswordAuthenticationToken userAuthentication() {
    return new UsernamePasswordAuthenticationToken(
        new AuthenticatedUser(2L, "USER", 1L, "user@test.com"),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
