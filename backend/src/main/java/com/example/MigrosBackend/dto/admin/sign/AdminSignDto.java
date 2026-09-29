package com.example.MigrosBackend.dto.admin.sign;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class AdminSignDto {
    @NotBlank
    private String adminName;
    @NotNull
    @Size(max = 72)
    @ToString.Exclude
    private String adminPassword;
}
