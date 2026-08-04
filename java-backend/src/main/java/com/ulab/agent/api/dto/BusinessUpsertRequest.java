package com.ulab.agent.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The editable fields of a business. The slug is derived from the name on
 * create and never changes afterwards, so it is not part of this request.
 */
public record BusinessUpsertRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 40) String phone,
        @Email @Size(max = 200) String email,
        @Size(max = 300) String address,
        @Size(max = 60) String timezone,
        String hoursJson) {
}
