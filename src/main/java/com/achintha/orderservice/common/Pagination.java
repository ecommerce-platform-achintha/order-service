package com.achintha.orderservice.common;

import com.achintha.orderservice.exception.ApiException;
import com.achintha.orderservice.exception.ErrorCode;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds a {@link Pageable} from the {@code page}, {@code size} and {@code sort} request parameters, accepting only
 * whitelisted sort fields and capping the page size (50 for user-facing lists, 100 for admin lists, section 1).
 */
public final class Pagination {

    public static final int MAX_SIZE = 50;
    public static final int MAX_ADMIN_SIZE = 100;

    private Pagination() {
    }

    /**
     * @param sort          {@code field} or {@code field,asc|desc}
     * @param sortableFields API field name to entity property
     */
    public static Pageable of(int page, int size, String sort, Map<String, String> sortableFields, int maxSize) {
        if (page < 0) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "page must be 0 or greater");
        }
        if (size < 1) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "size must be 1 or greater");
        }
        return PageRequest.of(page, Math.min(size, maxSize), parseSort(sort, sortableFields));
    }

    private static Sort parseSort(String sort, Map<String, String> sortableFields) {
        if (sort == null || sort.isBlank()) {
            return Sort.unsorted();
        }
        String[] parts = sort.split(",");
        String property = sortableFields.get(parts[0].trim());
        if (property == null || parts.length > 2) {
            throw ApiException.badRequest(ErrorCode.INVALID_SORT,
                    "sort must be one of " + sortableFields.keySet() + ", optionally followed by ,asc or ,desc");
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            direction = switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw ApiException.badRequest(ErrorCode.INVALID_SORT, "sort direction must be asc or desc");
            };
        }
        return Sort.by(direction, property);
    }

    /** Escapes LIKE wildcards so user input is matched literally (used with {@code ESCAPE '\'}). */
    public static String likeContains(String text) {
        String escaped = text.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
