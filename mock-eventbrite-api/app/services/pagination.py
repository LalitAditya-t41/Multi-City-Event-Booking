from __future__ import annotations

import math
from typing import Sequence, Tuple, List

from app.schemas.common import Pagination


def paginate(items: Sequence, page_size: int, page_number: int) -> tuple[list, Pagination]:
    total = len(items)
    if page_size <= 0:
        page_size = 50
    if page_number <= 0:
        page_number = 1
    page_count = max(1, math.ceil(total / page_size))
    start = (page_number - 1) * page_size
    end = start + page_size
    sliced = list(items)[start:end]
    has_more = page_number < page_count
    pagination = Pagination(
        object_count=total,
        page_count=page_count,
        page_size=page_size,
        page_number=page_number,
        has_more_items=has_more,
        continuation=None,
    )
    return sliced, pagination
