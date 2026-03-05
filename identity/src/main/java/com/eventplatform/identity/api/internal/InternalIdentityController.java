package com.eventplatform.identity.api.internal;

import com.eventplatform.identity.service.InternalIdentityQueryService;
import com.eventplatform.identity.service.InternalIdentityQueryService.DisplayNameResponse;
import com.eventplatform.identity.service.InternalIdentityQueryService.EmailResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/identity/users")
public class InternalIdentityController {

  private final InternalIdentityQueryService internalIdentityQueryService;

  public InternalIdentityController(InternalIdentityQueryService internalIdentityQueryService) {
    this.internalIdentityQueryService = internalIdentityQueryService;
  }

  @GetMapping("/{userId}/display-name")
  public DisplayNameResponse displayName(@PathVariable Long userId) {
    return internalIdentityQueryService.getDisplayName(userId);
  }

  @GetMapping("/{userId}/email")
  public EmailResponse email(@PathVariable Long userId) {
    return internalIdentityQueryService.getEmail(userId);
  }
}
