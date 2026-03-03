package com.eventplatform.admin.api.controller;

import com.eventplatform.admin.service.OrgOAuthService;
import com.eventplatform.shared.eventbrite.domain.OrganizationAuth;
import com.eventplatform.shared.security.Roles;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/orgs/{orgId}/eventbrite")
public class OrgEventbriteController {

    private final OrgOAuthService orgOAuthService;

    public OrgEventbriteController(OrgOAuthService orgOAuthService) {
        this.orgOAuthService = orgOAuthService;
    }

    @PostMapping("/connect")
    @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
    public AuthorizationResponse connect(@PathVariable("orgId") Long orgId) {
        String url = orgOAuthService.buildAuthorizationUrl(orgId);
        return new AuthorizationResponse(url);
    }

    @GetMapping("/callback")
    @ResponseStatus(HttpStatus.FOUND)
    public void callback(
        @PathVariable("orgId") Long orgId,
        @RequestParam("code") String code,
        @RequestParam("state") String state,
        HttpServletResponse response
    ) throws IOException {
        orgOAuthService.handleCallback(orgId, code, state);
        response.sendRedirect("/admin/dashboard");
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
    public OrgAuthStatusResponse status(@PathVariable("orgId") Long orgId) {
        OrganizationAuth auth = orgOAuthService.getStatus(orgId);
        return new OrgAuthStatusResponse(
            auth.getOrganizationId(),
            auth.getEbOrganizationId(),
            auth.getStatus().name(),
            auth.getExpiresAt(),
            auth.getCreatedAt()
        );
    }

    @DeleteMapping("/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
    public void disconnect(@PathVariable("orgId") Long orgId) {
        orgOAuthService.disconnect(orgId);
    }

    public record AuthorizationResponse(String authorizationUrl) {
    }

    public record OrgAuthStatusResponse(
        Long orgId,
        String ebOrganizationId,
        String status,
        Instant expiresAt,
        Instant connectedAt
    ) {
    }
}
