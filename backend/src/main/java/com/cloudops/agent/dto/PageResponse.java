package com.cloudops.agent.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Small envelope so the React app does not depend on Spring's Page JSON shape.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <E> PageResponse<E> of(Page<?> page, List<E> mappedContent) {
        return new PageResponse<>(
                mappedContent,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
