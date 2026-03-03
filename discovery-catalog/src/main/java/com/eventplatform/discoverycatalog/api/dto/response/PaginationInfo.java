package com.eventplatform.discoverycatalog.api.dto.response;

public record PaginationInfo(int page, int size, long totalElements, int totalPages) {
}
