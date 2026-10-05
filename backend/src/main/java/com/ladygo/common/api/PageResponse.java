package com.ladygo.common.api;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable JSON shape for a paginated list, independent of Spring Data's {@code Page} serialization. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
