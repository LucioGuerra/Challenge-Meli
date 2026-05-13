package com.meli.challenge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Meta(
    Integer total,
    Instant timestamp,
    String source,
    PageInfo page
) {
    public static Meta now() {
        return new Meta(null, Instant.now(), null, null);
    }

    public static Meta list(int total) {
        return new Meta(total, Instant.now(), null, null);
    }

    public static Meta withSource(String source) {
        return new Meta(null, Instant.now(), source, null);
    }

    public static Meta paginated(long totalElements, int page, int size) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new Meta((int) totalElements, Instant.now(), null,
            new PageInfo(page, size, totalPages, totalElements));
    }
}
