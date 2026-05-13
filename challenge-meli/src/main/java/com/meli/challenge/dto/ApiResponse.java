package com.meli.challenge.dto;

import java.util.List;

public record ApiResponse<T>(T data, Meta meta) {

    public static <T> ApiResponse<T> single(T data) {
        return new ApiResponse<>(data, Meta.now());
    }

    public static <T> ApiResponse<List<T>> list(List<T> data) {
        return new ApiResponse<>(data, Meta.list(data.size()));
    }

    public static <T> ApiResponse<T> withSource(T data, String source) {
        return new ApiResponse<>(data, Meta.withSource(source));
    }

    public static <T> ApiResponse<List<T>> paginated(List<T> data, long totalElements, int page, int size) {
        return new ApiResponse<>(data, Meta.paginated(totalElements, page, size));
    }
}
