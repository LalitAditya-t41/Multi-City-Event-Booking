package com.eventplatform.shared.common.dto;

import java.util.List;

public record PageResponse<T>(List<T> items, long totalCount, int page, int size) {
}
