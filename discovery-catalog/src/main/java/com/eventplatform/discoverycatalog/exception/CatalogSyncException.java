package com.eventplatform.discoverycatalog.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class CatalogSyncException extends BaseException {
    public CatalogSyncException(String message, Throwable cause) {
        super(message, "CATALOG_SYNC_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);
        initCause(cause);
    }

    public CatalogSyncException(String message) {
        super(message, "CATALOG_SYNC_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
