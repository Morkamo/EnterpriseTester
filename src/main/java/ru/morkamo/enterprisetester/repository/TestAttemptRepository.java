package ru.morkamo.enterprisetester.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import ru.morkamo.enterprisetester.model.TestAttempt;
import java.util.Optional;
import java.util.List;
import java.time.Instant;

public interface TestAttemptRepository extends JpaRepository<TestAttempt, Long> {
    Optional<TestAttempt> findFirstByTestIdAndUserIdAndFinishedAtIsNullOrderByIdDesc(Long testId, Long userId);

    List<TestAttempt> findByFinishedAtIsNullAndDeadlineLessThanEqual(Instant time);
    List<TestAttempt> findByTestIdAndFinishedAtIsNull(Long testId);
    List<TestAttempt> findByUserIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(Long userId);
    List<TestAttempt> findByTestIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(Long testId);
    List<TestAttempt> findByUserId(Long userId);
    List<TestAttempt> findByUserIdAndFinishedAtIsNull(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from TestAttempt a where a.id = :id and a.userId = :userId")
    Optional<TestAttempt> findForUpdate(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from TestAttempt a where a.id = :id")
    Optional<TestAttempt> findByIdForUpdate(Long id);
}
