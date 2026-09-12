package ru.morkamo.enterprisetester.repository;

import org.springframework.data.jpa.repository.Query;
import ru.morkamo.enterprisetester.extension.JpaRepositoryEnhanced;
import ru.morkamo.enterprisetester.model.User;

import java.util.Optional;

public interface UserRepository extends JpaRepositoryEnhanced<User, Long> {

    @Query(value = "select * from users where email = :email", nativeQuery = true)
    Optional<User> findByEmail(String email);
}
