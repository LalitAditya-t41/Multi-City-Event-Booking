package com.eventplatform.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.repository.ShowSlotRepository;
import com.eventplatform.shared.common.dto.SlotSummaryDto;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.exception.ResourceNotFoundException;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SlotSummaryReaderImplTest {

  @Mock private ShowSlotRepository showSlotRepository;

  @InjectMocks private SlotSummaryReaderImpl slotSummaryReader;

  @Test
  void should_return_SlotSummaryDto_with_all_fields_mapped_when_slot_exists() {
    ShowSlot slot = baseSlot();
    ReflectionTestUtils.setField(slot, "id", 55L);
    slot.setEbEventId("eb-55");

    when(showSlotRepository.findById(55L)).thenReturn(Optional.of(slot));

    SlotSummaryDto result = slotSummaryReader.getSlotSummary(55L);

    assertThat(result.slotId()).isEqualTo(55L);
    assertThat(result.status()).isEqualTo("DRAFT");
    assertThat(result.ebEventId()).isEqualTo("eb-55");
    assertThat(result.seatingMode()).isEqualTo(SeatingMode.RESERVED);
    assertThat(result.orgId()).isEqualTo(99L);
    assertThat(result.venueId()).isEqualTo(10L);
    assertThat(result.cityId()).isEqualTo(20L);
    assertThat(result.sourceSeatMapId()).isEqualTo("seat-map-1");
  }

  @Test
  void should_throw_ResourceNotFoundException_when_slot_absent() {
    when(showSlotRepository.findById(88L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> slotSummaryReader.getSlotSummary(88L))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Slot not found: 88");
  }

  @Test
  void should_map_orgId_from_ShowSlot_getOrganizationId() {
    ShowSlot slot = baseSlot();
    ReflectionTestUtils.setField(slot, "id", 101L);
    slot.setEbEventId("eb-101");

    when(showSlotRepository.findById(101L)).thenReturn(Optional.of(slot));

    SlotSummaryDto result = slotSummaryReader.getSlotSummary(101L);

    assertThat(result.orgId()).isEqualTo(99L);
  }

  @Test
  void should_map_status_as_string_via_enum_name() {
    ShowSlot slot = baseSlot();
    ReflectionTestUtils.setField(slot, "id", 102L);

    when(showSlotRepository.findById(102L)).thenReturn(Optional.of(slot));

    SlotSummaryDto result = slotSummaryReader.getSlotSummary(102L);

    assertThat(result.status()).isEqualTo("DRAFT");
  }

  private ShowSlot baseSlot() {
    return new ShowSlot(
        99L,
        10L,
        20L,
        "Title",
        "Description",
        ZonedDateTime.now().plusDays(1),
        ZonedDateTime.now().plusDays(1).plusHours(2),
        SeatingMode.RESERVED,
        100,
        false,
        null,
        "seat-map-1");
  }
}
