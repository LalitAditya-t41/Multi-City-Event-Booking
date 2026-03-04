package com.eventplatform.identity.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record UserSettingsUpsertRequest(
    String fullName,
    String phone,
    LocalDate dob,
    String address,
    @NotNull Long preferredCityOptionId,
    @NotNull @Size(max = 3) List<Long> preferredGenreOptionIds,
    @NotNull Boolean notificationOptIn
) {
}
