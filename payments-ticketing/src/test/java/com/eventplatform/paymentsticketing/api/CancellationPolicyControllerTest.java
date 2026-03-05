package com.eventplatform.paymentsticketing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.paymentsticketing.api.controller.CancellationPolicyController;
import com.eventplatform.paymentsticketing.api.dto.response.CancellationPolicyResponse;
import com.eventplatform.paymentsticketing.domain.enums.CancellationPolicyScope;
import com.eventplatform.paymentsticketing.exception.CancellationPolicyNotFoundException;
import com.eventplatform.paymentsticketing.exception.InvalidPolicyTierConfigException;
import com.eventplatform.paymentsticketing.service.CancellationPolicyService;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CancellationPolicyController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class CancellationPolicyControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CancellationPolicyService cancellationPolicyService;

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
  void should_create_policy_when_admin_is_authenticated() throws Exception {
    when(cancellationPolicyService.createPolicy(any(), any())).thenReturn(sampleResponse());

    mockMvc
        .perform(
            post("/api/v1/admin/cancellation-policies")
                .with(authentication(adminAuthentication()))
                .contentType("application/json")
                .content(
                    "{\"orgId\":88,\"tiers\":[{\"hoursBeforeEvent\":72,\"refundPercent\":100,\"sortOrder\":1},{\"hoursBeforeEvent\":null,\"refundPercent\":0,\"sortOrder\":2}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orgId").value(88))
        .andExpect(jsonPath("$.scope").value("ORG"));
  }

  @Test
  void should_return_400_when_policy_payload_is_invalid() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/cancellation-policies")
                .with(authentication(adminAuthentication()))
                .contentType("application/json")
                .content("{\"orgId\":88,\"tiers\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
  }

  @Test
  void should_return_403_when_non_admin_attempts_policy_create() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/cancellation-policies")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content(
                    "{\"orgId\":88,\"tiers\":[{\"hoursBeforeEvent\":72,\"refundPercent\":100,\"sortOrder\":1}]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void should_return_404_when_policy_id_not_found() throws Exception {
    when(cancellationPolicyService.getPolicy(999L))
        .thenThrow(new CancellationPolicyNotFoundException("not found"));

    mockMvc
        .perform(
            get("/api/v1/admin/cancellation-policies/999")
                .with(authentication(adminAuthentication())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("CANCELLATION_POLICY_NOT_FOUND"));
  }

  @Test
  void should_return_400_when_policy_update_has_invalid_tier_configuration() throws Exception {
    when(cancellationPolicyService.updatePolicy(any(), any()))
        .thenThrow(
            new InvalidPolicyTierConfigException(
                "Tier with null hoursBeforeEvent must be the last tier"));

    mockMvc
        .perform(
            put("/api/v1/admin/cancellation-policies/1")
                .with(authentication(adminAuthentication()))
                .contentType("application/json")
                .content(
                    "{\"orgId\":88,\"tiers\":[{\"hoursBeforeEvent\":null,\"refundPercent\":0,\"sortOrder\":1},{\"hoursBeforeEvent\":72,\"refundPercent\":100,\"sortOrder\":2}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_POLICY_TIER_CONFIG"));
  }

  @Test
  void should_return_effective_policy_for_org_when_requested() throws Exception {
    when(cancellationPolicyService.getEffectivePolicy(88L)).thenReturn(sampleResponse());

    mockMvc
        .perform(
            get("/api/v1/admin/cancellation-policies")
                .with(authentication(adminAuthentication()))
                .queryParam("orgId", "88"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orgId").value(88));
  }

  private CancellationPolicyResponse sampleResponse() {
    return new CancellationPolicyResponse(
        1L,
        88L,
        CancellationPolicyScope.ORG,
        1L,
        List.of(
            new CancellationPolicyResponse.TierResponse(11L, 72, 100, 1),
            new CancellationPolicyResponse.TierResponse(12L, null, 0, 2)));
  }

  private UsernamePasswordAuthenticationToken adminAuthentication() {
    return new UsernamePasswordAuthenticationToken(
        new AuthenticatedUser(1L, "ADMIN", null, null),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  private UsernamePasswordAuthenticationToken userAuthentication() {
    return new UsernamePasswordAuthenticationToken(
        new AuthenticatedUser(2L, "USER", null, null),
        null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
