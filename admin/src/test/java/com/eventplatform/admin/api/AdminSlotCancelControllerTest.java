package com.eventplatform.admin.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.admin.api.controller.AdminSlotCancelController;
import com.eventplatform.admin.service.OrgDashboardService;
import com.eventplatform.admin.service.OrgOAuthService;
import com.eventplatform.admin.service.client.SchedulingAdminClient;
import com.eventplatform.admin.service.client.SchedulingSlotCancelResponse;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.JwtTokenProvider;
import com.eventplatform.shared.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminSlotCancelController.class)
@ContextConfiguration(classes = com.eventplatform.admin.AdminTestApplication.class)
@Import({
  GlobalExceptionHandler.class,
  SecurityConfig.class,
  AdminSlotCancelControllerTest.MockBeans.class
})
class AdminSlotCancelControllerTest {

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

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SchedulingAdminClient schedulingAdminClient;
  @MockitoBean private OrgDashboardService orgDashboardService;
  @MockitoBean private OrgOAuthService orgOAuthService;

  @Test
  @WithMockUser(roles = "ADMIN")
  void should_delegate_slot_cancel_to_scheduling_module_when_admin_requests_cancel()
      throws Exception {
    when(schedulingAdminClient.cancelSlot(88L, 77L))
        .thenReturn(new SchedulingSlotCancelResponse(77L, "CANCELLED", true, "Slot cancelled"));

    mockMvc
        .perform(post("/api/v1/admin/slots/77/cancel").queryParam("orgId", "88"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slotId").value(77))
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  @WithMockUser(roles = "USER")
  void should_return_403_when_non_admin_requests_slot_cancel() throws Exception {
    mockMvc
        .perform(post("/api/v1/admin/slots/77/cancel").queryParam("orgId", "88"))
        .andExpect(status().isForbidden());
  }
}
