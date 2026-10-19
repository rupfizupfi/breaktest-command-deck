package ch.rupfizupfi.deck.data;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    User findByUsername(String username);

    @Query("select count(u) > 0 from User u join u.roles r where r = :role")
    boolean existsWithRole(@Param("role") Role role);
}
