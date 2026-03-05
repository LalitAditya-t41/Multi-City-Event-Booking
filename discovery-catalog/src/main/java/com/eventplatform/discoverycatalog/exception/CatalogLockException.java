package com.eventplatform.discoverycatalog.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class CatalogLockException extends BaseException {
  public CatalogLockException(String message) {
    super(message, "CATALOG_LOCK_ERROR", HttpStatus.LOCKED);
  }
}
