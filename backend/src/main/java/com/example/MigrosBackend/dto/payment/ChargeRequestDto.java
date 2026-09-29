package com.example.MigrosBackend.dto.payment;

import jakarta.validation.constraints.NotBlank;

public record ChargeRequestDto(@NotBlank String token) {
}
