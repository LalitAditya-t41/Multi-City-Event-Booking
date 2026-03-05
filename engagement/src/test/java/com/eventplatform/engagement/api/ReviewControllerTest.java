package com.eventplatform.engagement.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventplatform.engagement.api.controller.ReviewController;
import com.eventplatform.engagement.api.dto.response.ReviewResponse;
import com.eventplatform.engagement.api.dto.response.ReviewSubmitResponse;
import com.eventplatform.engagement.api.dto.response.ReviewSummaryResponse;
import com.eventplatform.engagement.domain.enums.AttendanceVerificationStatus;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import com.eventplatform.engagement.exception.ReviewAlreadySubmittedException;
import com.eventplatform.engagement.exception.ReviewNotEligibleException;
import com.eventplatform.engagement.service.ReviewRatingSummaryService;
import com.eventplatform.engagement.service.ReviewService;
import com.eventplatform.shared.common.exception.GlobalExceptionHandler;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.JwtAuthenticationFilter;
import com.eventplatform.shared.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ReviewController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReviewService reviewService;
    @MockBean
    private ReviewRatingSummaryService reviewRatingSummaryService;
    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUpFilterPassThrough() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void should_return_201_when_submit_review_request_is_valid() throws Exception {
        when(reviewService.submitReview(any(), any()))
            .thenReturn(new ReviewSubmitResponse(55L, ReviewStatus.PENDING_MODERATION, "under moderation"));

        mockMvc.perform(post("/api/v1/engagement/reviews")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"eventId\":1001,\"rating\":4,\"title\":\"Great\",\"body\":\"Great show\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reviewId").value(55))
            .andExpect(jsonPath("$.status").value("PENDING_MODERATION"));
    }

    @Test
    void should_return_403_when_submit_review_not_eligible() throws Exception {
        when(reviewService.submitReview(any(), any()))
            .thenThrow(new ReviewNotEligibleException("not eligible", "REVIEW_NOT_ELIGIBLE"));

        mockMvc.perform(post("/api/v1/engagement/reviews")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"eventId\":1001,\"rating\":4,\"title\":\"Great\",\"body\":\"Great show\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_ELIGIBLE"));
    }

    @Test
    void should_return_409_when_submit_review_is_duplicate() throws Exception {
        when(reviewService.submitReview(any(), any())).thenThrow(new ReviewAlreadySubmittedException());

        mockMvc.perform(post("/api/v1/engagement/reviews")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"eventId\":1001,\"rating\":4,\"title\":\"Great\",\"body\":\"Great show\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("REVIEW_ALREADY_SUBMITTED"));
    }

    @Test
    void should_return_400_when_submit_review_rating_is_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/engagement/reviews")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"eventId\":1001,\"rating\":0,\"title\":\"Great\",\"body\":\"Great show\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_400_when_submit_review_title_is_blank() throws Exception {
        mockMvc.perform(post("/api/v1/engagement/reviews")
                .with(authentication(userAuthentication()))
                .contentType("application/json")
                .content("{\"eventId\":1001,\"rating\":4,\"title\":\"\",\"body\":\"Great show\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void should_return_401_when_submit_review_without_jwt() throws Exception {
        mockMvc.perform(post("/api/v1/engagement/reviews")
                .contentType("application/json")
                .content("{\"eventId\":1001,\"rating\":4,\"title\":\"Great\",\"body\":\"Great show\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void should_return_200_for_public_published_reviews_endpoint() throws Exception {
        when(reviewService.listPublishedReviews(any(), any())).thenReturn(new PageImpl<>(List.of(
            new ReviewResponse(1L, 1001L, 5, "Great", "Loved it", ReviewStatus.PUBLISHED,
                AttendanceVerificationStatus.EB_VERIFIED, null,
                Instant.parse("2026-03-05T10:00:00Z"), Instant.parse("2026-03-05T10:10:00Z"), "Jane D")
        ), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/engagement/reviews/events/1001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].reviewId").value(1));
    }

    @Test
    void should_apply_default_sort_published_at_desc_when_no_sort_param() throws Exception {
        when(reviewService.listPublishedReviews(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/engagement/reviews/events/1001"))
            .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewService).listPublishedReviews(any(), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getSort().getOrderFor("publishedAt")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("publishedAt").isDescending()).isTrue();
    }

    @Test
    void should_return_200_for_review_summary_endpoint() throws Exception {
        when(reviewRatingSummaryService.getSummary(1001L))
            .thenReturn(new ReviewSummaryResponse(1001L, new BigDecimal("4.2"), 142L, Map.of(5, 80L), Instant.now()));

        mockMvc.perform(get("/api/v1/engagement/reviews/events/1001/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.averageRating").value(4.2));
    }

    @Test
    void should_return_200_for_my_reviews_when_authenticated() throws Exception {
        when(reviewService.listMyReviews(any(), any())).thenReturn(new PageImpl<>(List.of(
            new ReviewResponse(1L, 1001L, 5, "Great", "Loved it", ReviewStatus.PUBLISHED,
                AttendanceVerificationStatus.EB_VERIFIED, null,
                Instant.parse("2026-03-05T10:00:00Z"), Instant.parse("2026-03-05T10:10:00Z"), "Jane D")
        ), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/engagement/reviews/me").with(authentication(userAuthentication())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("PUBLISHED"));
    }

    @Test
    void should_return_401_for_my_reviews_without_jwt() throws Exception {
        mockMvc.perform(get("/api/v1/engagement/reviews/me"))
            .andExpect(status().isUnauthorized());
    }

    private UsernamePasswordAuthenticationToken userAuthentication() {
        return new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(1L, "USER", 1L, "user@test.com"),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
