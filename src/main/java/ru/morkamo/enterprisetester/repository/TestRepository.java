package ru.morkamo.enterprisetester.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.morkamo.enterprisetester.model.TestDefinition;
import java.util.List;

public interface TestRepository extends JpaRepository<TestDefinition, Long> {
    @org.springframework.data.jpa.repository.Query("""
            select distinct t from TestDefinition t join t.testers u
            where u.id = :userId and t.testClosed = false
            and not exists (select a.id from TestAttempt a
                where a.testId = t.id and a.userId = :userId and a.finishedAt is not null)
            order by t.id
            """)
    List<TestDefinition> findAvailableForUser(Long userId);

    List<TestDefinition> findByOwnerIdOrderByIdDesc(Long ownerId);
    List<TestDefinition> findAllByOrderByIdDesc();
}
