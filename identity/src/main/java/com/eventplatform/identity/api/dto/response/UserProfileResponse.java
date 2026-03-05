package com.eventplatform.identity.api.dto.response;

public record UserProfileResponse(Long userId, String email, String role, String status) {}
