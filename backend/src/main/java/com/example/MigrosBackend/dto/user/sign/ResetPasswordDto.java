package com.example.MigrosBackend.dto.user.sign;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class ResetPasswordDto {
    @NotBlank
    @ToString.Exclude
    private String token;
    @NotNull
    @Size(max = 72)
    @ToString.Exclude
    private String userPassword;
}
