package ru.morkamo.enterprisetester.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.morkamo.enterprisetester.model.Question;
import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    List<Question> findByOwnerIdOrderByIdDesc(Long ownerId);
    List<Question> findAllByOrderByIdDesc();
}
