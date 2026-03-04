package com.eventplatform.bookinginventory.api.controller;

import com.eventplatform.bookinginventory.api.dto.request.AbandonCartRequest;
import com.eventplatform.bookinginventory.api.dto.request.AddSeatRequest;
import com.eventplatform.bookinginventory.api.dto.request.ConfirmCartRequest;
import com.eventplatform.bookinginventory.api.dto.response.CartResponse;
import com.eventplatform.bookinginventory.service.CartService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/booking/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @PostMapping("/add-seat")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public CartResponse addSeat(Authentication authentication, @Valid @RequestBody AddSeatRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return cartService.addSeat(user.userId(), user.orgId(), user.role(), request);
    }

    @DeleteMapping("/items/{itemId}")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public CartResponse removeItem(Authentication authentication, @PathVariable Long itemId) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return cartService.removeItem(user.userId(), itemId);
    }

    @GetMapping("/{cartId}")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public CartResponse getCart(Authentication authentication, @PathVariable Long cartId) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return cartService.getCart(user.userId(), cartId);
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public CartResponse confirm(Authentication authentication, @Valid @RequestBody ConfirmCartRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return cartService.confirm(user.userId(), user.orgId(), user.email(), request);
    }

    @PostMapping("/abandon")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public Map<String, String> abandon(Authentication authentication, @Valid @RequestBody AbandonCartRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        cartService.abandon(user.userId(), request);
        return Map.of("message", "Cart abandoned. All seat locks released.");
    }
}
