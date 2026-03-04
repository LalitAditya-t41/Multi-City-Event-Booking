package com.eventplatform.paymentsticketing.api.controller;

import com.eventplatform.paymentsticketing.api.dto.request.PaymentConfirmRequest;
import com.eventplatform.paymentsticketing.api.dto.request.PaymentFailedRequest;
import com.eventplatform.paymentsticketing.api.dto.response.BookingResponse;
import com.eventplatform.paymentsticketing.api.dto.response.CheckoutInitResponse;
import com.eventplatform.paymentsticketing.service.PaymentService;
import com.eventplatform.shared.security.AuthenticatedUser;
import com.eventplatform.shared.security.Roles;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/checkout/{cartId}")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public CheckoutInitResponse getCheckout(Authentication authentication, @PathVariable Long cartId) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return paymentService.getCheckout(cartId, user.userId());
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public BookingResponse confirm(Authentication authentication, @Valid @RequestBody PaymentConfirmRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return paymentService.confirmPayment(user.userId(), request.paymentIntentId());
    }

    @PostMapping("/failed")
    @PreAuthorize("hasRole('" + Roles.USER + "')")
    public Map<String, String> failed(@Valid @RequestBody PaymentFailedRequest request) {
        paymentService.handleFailure(request.paymentIntentId(), null, "frontend_reported_failure");
        return Map.of("message", "Seat locks released. Cart cleared.");
    }
}
