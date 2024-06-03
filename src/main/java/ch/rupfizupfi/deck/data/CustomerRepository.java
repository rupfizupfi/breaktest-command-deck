package ch.rupfizupfi.deck.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerRepository  extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {
}
