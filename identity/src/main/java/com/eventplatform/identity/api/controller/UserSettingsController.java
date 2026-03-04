package com.eventplatform.identity.api.controller;

import com.eventplatform.identity.api.dto.request.UserSettingsUpsertRequest;
import com.eventplatform.identity.api.dto.response.UserSettingsResponse;
import com.eventplatform.identity.service.UserSettingsService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/settings")
public class UserSettingsController {

    private final UserSettingsService userSettingsService;

    public UserSettingsController(UserSettingsService userSettingsService) {
        this.userSettingsService = userSettingsService;
    }

    @GetMapping
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public UserSettingsResponse getMySettings(Authentication authentication) {
        return userSettingsService.getMySettings(((AuthenticatedUser) authentication.getPrincipal()).userId());
    }

    @PutMapping
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public UserSettingsResponse upsertMySettings(
        Authentication authentication,
        @Valid @RequestBody UserSettingsUpsertRequest request
    ) {
        return userSettingsService.upsertMySettings(((AuthenticatedUser) authentication.getPrincipal()).userId(), request);
    }
}
