package ch.rupfizupfi.deck.hilla.crud;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/** A client-supplied page size is bounded at 500 (B-15); page number and sort survive. */
class PageCapTest {

    private static final Sort BY_NAME = Sort.by(Sort.Direction.DESC, "name");

    @Test
    void oversizedPageIsCappedKeepingPageNumberAndSort() {
        Pageable capped = PageCap.clamp(PageRequest.of(3, 1_000_000, BY_NAME));

        assertThat(capped.getPageSize()).isEqualTo(500);
        assertThat(capped.getPageNumber()).isEqualTo(3);
        assertThat(capped.getSort()).isEqualTo(BY_NAME);
    }

    @Test
    void integerMaxValueIsCapped() {
        assertThat(PageCap.clamp(PageRequest.of(0, Integer.MAX_VALUE)).getPageSize()).isEqualTo(500);
    }

    @Test
    void unpagedBecomesTheFirstCappedPage() {
        Pageable capped = PageCap.clamp(Pageable.unpaged(BY_NAME));

        assertThat(capped.isPaged()).isTrue();
        assertThat(capped.getPageNumber()).isZero();
        assertThat(capped.getPageSize()).isEqualTo(500);
        assertThat(capped.getSort()).isEqualTo(BY_NAME);
    }

    @Test
    void missingPageableBecomesTheFirstCappedPage() {
        Pageable capped = PageCap.clamp(null);

        assertThat(capped.getPageNumber()).isZero();
        assertThat(capped.getPageSize()).isEqualTo(500);
    }

    @Test
    void normalPageIsUnchanged() {
        Pageable page = PageRequest.of(2, 50, BY_NAME);

        assertThat(PageCap.clamp(page)).isSameAs(page);
    }

    /** The OwnerSelector's order omits nullHandling; JPA's query builder threw on the null. */
    @Test
    void orderWithoutNullHandlingGetsNative() {
        Sort.Order bare = new Sort.Order(Sort.Direction.ASC, "name", false, null);
        Pageable capped = PageCap.clamp(PageRequest.of(1, 100, Sort.by(bare)));

        assertThat(capped.getPageNumber()).isEqualTo(1);
        assertThat(capped.getPageSize()).isEqualTo(100);
        Sort.Order order = capped.getSort().getOrderFor("name");
        assertThat(order).isNotNull();
        assertThat(order.getNullHandling()).isEqualTo(Sort.NullHandling.NATIVE);
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void pageAtTheCapIsUnchanged() {
        Pageable page = PageRequest.of(0, 500);

        assertThat(PageCap.clamp(page)).isSameAs(page);
    }
}
