package ru.morkamo.enterprisetester.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.morkamo.enterprisetester.model.TestDefinition;
import java.util.List;

public interface TestRepository extends JpaRepository<TestDefinition, Long> {
    List<TestDefinition> findByTestClosedFalseOrderByIdAsc();
}
