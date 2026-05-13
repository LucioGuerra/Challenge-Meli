package com.meli.challenge.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaginatorTest {

    private final List<Integer> items = List.of(1, 2, 3, 4, 5);

    @Test
    void firstPage_returnsFirstSlice() {
        assertThat(Paginator.paginate(items, 0, 2)).containsExactly(1, 2);
    }

    @Test
    void middlePage_returnsCorrectSlice() {
        assertThat(Paginator.paginate(items, 1, 2)).containsExactly(3, 4);
    }

    @Test
    void lastPagePartial_returnsRemainingElements() {
        assertThat(Paginator.paginate(items, 2, 2)).containsExactly(5);
    }

    @Test
    void pageBeyondData_returnsEmpty() {
        assertThat(Paginator.paginate(items, 10, 5)).isEmpty();
    }

    @Test
    void emptyList_returnsEmpty() {
        assertThat(Paginator.paginate(List.of(), 0, 10)).isEmpty();
    }

    @Test
    void sizeBiggerThanList_returnsEverything() {
        assertThat(Paginator.paginate(items, 0, 100)).containsExactlyElementsOf(items);
    }

    @Test
    void exactPageBoundary_returnsFullSlice() {
        assertThat(Paginator.paginate(items, 0, 5)).containsExactlyElementsOf(items);
    }
}
