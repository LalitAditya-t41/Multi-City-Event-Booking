package com.eventplatform.paymentsticketing.event.listener;

import com.eventplatform.paymentsticketing.service.PaymentService;
import com.eventplatform.shared.common.event.published.CartAssembledEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component("paymentsTicketingCartAssembledListener")
public class CartAssembledListener {

  private final PaymentService paymentService;

  public CartAssembledListener(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @EventListener
  public void onCartAssembled(CartAssembledEvent event) {
    paymentService.createCheckout(event);
  }
}
