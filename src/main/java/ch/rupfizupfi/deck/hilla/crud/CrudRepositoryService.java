package ch.rupfizupfi.deck.hilla.crud;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;

public class CrudRepositoryService<T, R extends CrudRepository<T, Long> & JpaSpecificationExecutor<T>> extends com.vaadin.hilla.crud.CrudRepositoryService<T, Long, R> {
    public CrudRepository<T, Long> getCrudRepository() {
        return getRepository();
    }
}
