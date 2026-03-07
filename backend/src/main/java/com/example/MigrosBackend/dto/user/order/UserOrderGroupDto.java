package com.example.MigrosBackend.dto.user.order;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class UserOrderGroupDto {
    private long orderGroupId;
    private LocalDateTime createdAt;
    private List<UserOrderDetailDto> items;
}
