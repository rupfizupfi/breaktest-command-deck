package ch.rupfizupfi.deck.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TestParameterRepository extends JpaRepository<TestParameter, Long>, JpaSpecificationExecutor<TestParameter> {
}
