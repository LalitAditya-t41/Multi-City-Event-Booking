package com.eventplatform.promotions.domain.enums;

public enum EbSyncStatus {
    NOT_SYNCED,
    SYNC_PENDING,
    SYNCED,
    SYNC_FAILED,
    DELETE_BLOCKED,
    EB_DELETED_EXTERNALLY,
    DRIFT_DETECTED,
    CANNOT_RESYNC
}
