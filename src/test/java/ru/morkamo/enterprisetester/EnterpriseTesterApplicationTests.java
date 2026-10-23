package ru.morkamo.enterprisetester;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import ru.morkamo.enterprisetester.service.ManagementService;
import java.util.List;
import java.math.BigDecimal;
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
        var question = management.saveQuestion(null, "Заголовок", "Проверка сохранения", false,
                List.of("Да", "Нет"), List.of(new BigDecimal("3.3333"), BigDecimal.ZERO), null, null, null, false);
        question.getImageNames().add("test.png");
        entityManager.flush();
        assertEquals(2, question.getAnswers().size());
        assertEquals(1, question.getImageNames().size());
        management.saveQuestion(question.getId(), "Заголовок", "Проверка изменения", false,
                List.of("Первый", "Второй"), List.of(BigDecimal.ZERO, new BigDecimal("2.10")), null, null, null, false);
        entityManager.flush();
        assertEquals("Проверка изменения", question.getText());
        entityManager.clear();
        var saved = entityManager.find(ru.morkamo.enterprisetester.model.Question.class, question.getId());
        assertEquals("Заголовок", saved.getTitle());
        assertEquals("2.1", saved.getAnswers().get(1).getPointsText());
    }

}
