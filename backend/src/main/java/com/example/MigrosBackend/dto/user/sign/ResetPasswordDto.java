package com.example.MigrosBackend.dto.user.sign;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResetPasswordDto {
    private String token;
    private String userPassword;
}
