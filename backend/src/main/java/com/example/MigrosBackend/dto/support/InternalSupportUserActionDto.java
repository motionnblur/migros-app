package com.example.MigrosBackend.dto.support;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InternalSupportUserActionDto {
    @NotBlank
    private String userMail;
}
