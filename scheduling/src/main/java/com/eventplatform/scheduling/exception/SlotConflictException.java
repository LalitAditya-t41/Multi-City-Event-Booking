package com.eventplatform.scheduling.exception;

import com.eventplatform.scheduling.domain.value.ConflictAlternativeResponse;
import com.eventplatform.shared.common.exception.BusinessRuleException;

public class SlotConflictException extends BusinessRuleException {
    private final ConflictAlternativeResponse alternatives;

    public SlotConflictException(String message, ConflictAlternativeResponse alternatives) {
        super(message, "SLOT_CONFLICT", alternatives);
        this.alternatives = alternatives;
    }

    public ConflictAlternativeResponse getAlternatives() {
        return alternatives;
    }
}
