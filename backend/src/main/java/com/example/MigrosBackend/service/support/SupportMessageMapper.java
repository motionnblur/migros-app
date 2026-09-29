package com.example.MigrosBackend.service.support;

import com.example.MigrosBackend.dto.user.support.SupportMessageDto;
import com.example.MigrosBackend.entity.user.SupportMessageEntity;

import java.util.List;

final class SupportMessageMapper {

    private SupportMessageMapper() {
    }

    static SupportMessageDto toDto(SupportMessageEntity entity) {
        return new SupportMessageDto(
                entity.getId(),
                entity.getSender(),
                entity.getMessage(),
                entity.getCreatedAt(),
                entity.getEditedAt()
        );
    }

    static List<SupportMessageDto> toDtos(List<SupportMessageEntity> entities) {
        return entities.stream()
                .map(SupportMessageMapper::toDto)
                .toList();
    }
}
