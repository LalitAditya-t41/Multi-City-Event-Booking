package com.eventplatform.discoverycatalog.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class CatalogNotFoundException extends ResourceNotFoundException {
  public CatalogNotFoundException(String message) {
    super(message, "CATALOG_NOT_FOUND");
  }
}
