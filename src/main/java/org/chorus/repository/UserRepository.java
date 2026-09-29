package org.chorus.repository;

import org.chorus.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsernameAndDiscriminator(String username, String discriminator);
    boolean existsByEmail(String email);
}
