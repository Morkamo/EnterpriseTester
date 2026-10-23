package ru.morkamo.enterprisetester;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.morkamo.enterprisetester.model.Answer;
import ru.morkamo.enterprisetester.service.QuestionImportService;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuestionImportTests {
    private final QuestionImportService importer = new QuestionImportService();

    private MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "questions.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void semicolonCsvSupportsBomDecimalCommaEmptyPointsAndExplicitMultipleChoice() {
        var questions = importer.parse(csv("\uFEFFВопрос 1;Содержание;Ответ 1;0.5;1\r\n"
                + ";;Ответ 2;0,5;\r\n;;Ответ 3;;\r\n\r\n"
                + "Вопрос 2;Условие;Ответ 1;3.3333;0\r\n;;Ответ 2;2.10;\r\n;;Ответ 3;;\r\n"));
        assertEquals(2, questions.size());
        var first = questions.get(0);
        assertEquals("Вопрос 1", first.getTitle());
        assertEquals("Содержание", first.getText());
        assertTrue(first.isMultipleAllowed());
        assertEquals(List.of("0.5", "0.5", "0"), first.getAnswers().stream().map(Answer::getPointsText).toList());
        assertFalse(questions.get(1).isMultipleAllowed());
        assertEquals(List.of("3.33", "2.1", "0"), questions.get(1).getAnswers().stream().map(Answer::getPointsText).toList());
    }

    @Test
    void commaCsvHandlesQuotedDelimitersEscapedQuotesAndMultilineContent() {
        var question = importer.parse(csv("\"Заголовок, один\",\"Первая строка\nВторая; строка\",\"Ответ \"\"да\"\"\",\"0,6\",\n"
                + ",,Нет,,\n")).get(0);
        assertEquals("Заголовок, один", question.getTitle());
        assertEquals("Первая строка\nВторая; строка", question.getText());
        assertEquals("Ответ \"да\"", question.getAnswers().get(0).getText());
        assertEquals("0.6", question.getAnswers().get(0).getPointsText());
        assertFalse(question.isMultipleAllowed());
    }

    @Test
    void windows1251AndMissingFinalColumnAreAccepted() {
        var file = new MockMultipartFile("file", "questions.CSV", "text/plain",
                "Заголовок;Содержание;Да;1\n;;Нет;\n".getBytes(Charset.forName("windows-1251")));
        var question = importer.parse(file).get(0);
        assertEquals("Заголовок", question.getTitle());
        assertFalse(question.isMultipleAllowed());
    }

    @Test
    void invalidRowsRejectTheWholeFileWithAnExplanatoryError() {
        String valid = "Первый;Текст;Да;1;\n;;Нет;;\n";
        for (String invalid : List.of(
                "Заголовок;;Да;1;\n;;Нет;;\n",
                "Заголовок;Текст;Да;1;2\n;;Нет;;\n",
                "Заголовок;Текст;;1;\n;;Нет;;\n",
                "Заголовок;Текст;Да;-1;\n;;Нет;;\n",
                "Заголовок;Текст;Да;NaN;\n;;Нет;;\n",
                "Заголовок;Текст;Да;10000000000000000;\n;;Нет;;\n",
                "Заголовок;Текст;Да;0.004;\n;;Нет;;\n",
                "Заголовок;Текст;Да;1;\n",
                "Заголовок;Текст;Да;1;\n;Лишний текст;Нет;;\n",
                "Заголовок;Текст;Да;1;\n;;Нет;;1\n",
                "Заголовок;Текст;Да;1;0;Лишняя колонка\n"
        )) {
            var error = assertThrows(ResponseStatusException.class, () -> importer.parse(csv(valid + invalid)), invalid);
            assertEquals(400, error.getStatusCode().value());
            assertTrue(error.getReason().startsWith("Строка "), error.getReason());
        }
    }

    @Test
    void emptyNonCsvAndBrokenQuotesAreRejected() {
        for (String text : List.of("", "\n;;;;\n", "wrong format", ";;Без вопроса;1;\n", "Вопрос;Текст;\"Ответ;1;\n")) {
            assertThrows(ResponseStatusException.class, () -> importer.parse(csv(text)));
        }
        assertThrows(ResponseStatusException.class, () -> importer.parse(new MockMultipartFile(
                "file", "questions.xlsx", "text/csv", "A;B;C;1;0".getBytes(StandardCharsets.UTF_8))));
        assertThrows(ResponseStatusException.class, () -> importer.parse(new MockMultipartFile(
                "file", "questions.csv", "text/csv", new byte[10 * 1024 * 1024 + 1])));
    }
}
