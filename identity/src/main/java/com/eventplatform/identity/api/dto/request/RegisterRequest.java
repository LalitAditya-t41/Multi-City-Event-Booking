package com.eventplatform.identity.api.dto.request;

import com.eventplatform.shared.common.exception.ValidationException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;

public class RegisterRequest {

    @Email
    @NotBlank
    private String email;

    @NotBlank
    @Size(min = 8, max = 128)
    private String password;

    @JsonIgnore
    private final Map<String, Object> unknownFields = new HashMap<>();

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    @JsonAnySetter
    public void captureUnknownField(String field, Object value) {
        if (!"email".equals(field) && !"password".equals(field)) {
            unknownFields.put(field, value);
        }
    }

    public void validateNoUnknownFields() {
        if (!unknownFields.isEmpty()) {
            throw new ValidationException("Unknown fields are not allowed", "UNKNOWN_FIELDS");
        }
    }
}
