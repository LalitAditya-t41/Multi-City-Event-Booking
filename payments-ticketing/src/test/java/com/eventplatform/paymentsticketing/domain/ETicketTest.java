package com.eventplatform.paymentsticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventplatform.paymentsticketing.domain.enums.ETicketStatus;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ETicketTest {

  @Test
  void should_encode_qr_code_data_as_booking_ref_colon_item_id() {
    String raw = "BK-20260304-001:10";
    String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

    ETicket ticket = new ETicket(1L, 10L, encoded, "/tickets/BK-20260304-001/10.pdf");

    assertThat(ticket.getQrCodeData()).isEqualTo(encoded);
  }

  @Test
  void should_transition_active_to_voided_when_void_ticket_called() {
    ETicket ticket = new ETicket(1L, 10L, "qr", "/tickets/BK-20260304-001/10.pdf");

    ticket.voidTicket();

    assertThat(ticket.getStatus()).isEqualTo(ETicketStatus.VOIDED);
  }

  @Test
  void should_keep_ticket_voided_when_void_ticket_called_multiple_times() {
    ETicket ticket = new ETicket(1L, 10L, "qr", "/tickets/BK-20260304-001/10.pdf");

    ticket.voidTicket();
    ticket.voidTicket();

    assertThat(ticket.getStatus()).isEqualTo(ETicketStatus.VOIDED);
  }
}
