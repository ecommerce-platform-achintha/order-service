package com.achintha.orderservice.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A mandatory reason (unblocking, admin decisions); sanitized before it is stored. */
public record ReasonRequest(@NotBlank @Size(max = Validation.REASON_MAX) String reason) {
}
