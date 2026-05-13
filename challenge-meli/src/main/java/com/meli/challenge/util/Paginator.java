package com.meli.challenge.util;

import java.util.List;

public final class Paginator {

    private Paginator() {}

    public static <T> List<T> paginate(List<T> source, int page, int size) {
        int from = page * size;
        if (from >= source.size()) {
            return List.of();
        }
        int to = Math.min(from + size, source.size());
        return source.subList(from, to);
    }
}
