package ch.rupfizupfi.deck.hilla.crud;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;

/**
 * Upper bound on one page of a browser-callable {@code list}. Hilla hands the client's
 * {@link Pageable} straight through, so without this a caller could ask for
 * {@code pageSize=Integer.MAX_VALUE} and have the whole table loaded and serialised in one call.
 */
public final class PageCap {
    /** Owner decision (B-15). The grids ask for 50, combo boxes and selectors for 100. */
    public static final int MAX_PAGE_SIZE = 500;

    private PageCap() {
    }

    /**
     * Keeps page number and sort; a size above {@link #MAX_PAGE_SIZE} becomes the cap, and an
     * unpaged (or missing) request becomes the first capped page.
     */
    public static Pageable clamp(@Nullable Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, MAX_PAGE_SIZE);
        }
        if (pageable.isUnpaged()) {
            return PageRequest.of(0, MAX_PAGE_SIZE, sortOf(pageable));
        }
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, sortOf(pageable));
        }
        return pageable;
    }

    private static Sort sortOf(Pageable pageable) {
        Sort sort = pageable.getSort();
        return sort == null ? Sort.unsorted() : sort;
    }
}
