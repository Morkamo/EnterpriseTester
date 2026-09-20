package ru.morkamo.enterprisetester.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import ru.morkamo.enterprisetester.model.User;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query(value = "select * from users where email = :email", nativeQuery = true)
    Optional<User> findByEmail(String email);
    List<User> findAllByOrderByLastNameAscFirstNameAsc();
    List<User> findByDeletedFalseOrderByLastNameAscFirstNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(Long id);
}
