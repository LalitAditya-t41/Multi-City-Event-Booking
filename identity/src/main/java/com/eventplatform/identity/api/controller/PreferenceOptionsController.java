package com.eventplatform.identity.api.controller;

import com.eventplatform.identity.api.dto.response.PreferenceOptionsResponse;
import com.eventplatform.identity.service.PreferenceOptionsService;
import com.eventplatform.shared.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preferences")
public class PreferenceOptionsController {

    private final PreferenceOptionsService preferenceOptionsService;

    public PreferenceOptionsController(PreferenceOptionsService preferenceOptionsService) {
        this.preferenceOptionsService = preferenceOptionsService;
    }

    @GetMapping("/options")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public PreferenceOptionsResponse listOptions() {
        return preferenceOptionsService.listActiveOptions();
    }
}
