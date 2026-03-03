package com.eventplatform.discoverycatalog.exception;

import com.eventplatform.shared.common.exception.ValidationException;

public class InvalidCatalogSearchException extends ValidationException {
    public InvalidCatalogSearchException(String message) {
        super(message, "INVALID_CATALOG_SEARCH");
    }
}
