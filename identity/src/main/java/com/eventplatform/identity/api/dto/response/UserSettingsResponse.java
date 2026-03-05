package com.eventplatform.identity.api.dto.response;

import java.time.LocalDate;
import java.util.List;

public record UserSettingsResponse(
    String fullName,
    String phone,
    LocalDate dob,
    String address,
    PreferenceSelectionResponse preferredCity,
    List<PreferenceSelectionResponse> preferredGenres,
    boolean notificationOptIn) {}
