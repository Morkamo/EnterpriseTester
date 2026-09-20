package ru.morkamo.enterprisetester;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import ru.morkamo.enterprisetester.service.ManagementService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class EnterpriseTesterApplicationTests {

    @Autowired ManagementService management;
    @Autowired EntityManager entityManager;

    @Test
    void contextLoads() {
    }

    @Test
    @Transactional
    void questionAndAnswersCanBeInsertedIntoMySql() {
        var question = management.saveQuestion(null, "Проверка сохранения", false,
                List.of("Да", "Нет"), List.of(1, 0), null, null, null, false);
        question.getImageNames().add("test.png");
        entityManager.flush();
        assertEquals(2, question.getAnswers().size());
        assertEquals(1, question.getImageNames().size());
        management.saveQuestion(question.getId(), "Проверка изменения", false,
                List.of("Первый", "Второй"), List.of(0, 2), null, null, null, false);
        entityManager.flush();
        assertEquals("Проверка изменения", question.getText());
    }

}
