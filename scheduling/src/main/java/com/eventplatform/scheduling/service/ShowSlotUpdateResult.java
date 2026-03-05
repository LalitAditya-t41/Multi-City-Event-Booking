package com.eventplatform.scheduling.service;

import com.eventplatform.scheduling.domain.ShowSlot;

public record ShowSlotUpdateResult(ShowSlot slot, boolean ebSyncFailed) {}
