package com.eventplatform.identity.api.controller;

import com.eventplatform.identity.api.dto.response.UserWalletResponse;
import com.eventplatform.identity.service.UserWalletService;
import com.eventplatform.shared.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/wallet")
public class UserWalletController {

    private final UserWalletService userWalletService;

    public UserWalletController(UserWalletService userWalletService) {
        this.userWalletService = userWalletService;
    }

    @GetMapping
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public UserWalletResponse getMyWallet(Authentication authentication) {
        return userWalletService.getWallet((Long) authentication.getPrincipal());
    }
}
