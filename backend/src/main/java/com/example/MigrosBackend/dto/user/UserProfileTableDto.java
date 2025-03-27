package com.example.MigrosBackend.dto.user;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserProfileTableDto {
    private String userFirstName;
    private String userLastName;
    private String userAddress;
    private String userAddress2;
    private String userTown;
    private String userCountry;
    private String userPostalCode;
}
