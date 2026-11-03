package ru.morkamo.enterprisetester;

import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import ru.morkamo.enterprisetester.config.TestSettings;
import ru.morkamo.enterprisetester.config.MultiAnswerMode;
import ru.morkamo.enterprisetester.model.*;
import ru.morkamo.enterprisetester.service.TestService;
import ru.morkamo.enterprisetester.service.ManagementService;
import java.time.Instant;
import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:passing;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "testing.upload-dir=target/test-question-images"
})
@Transactional
class TestPassingTests {
    @Autowired TestService service;
    @Autowired ManagementService management;
    @Autowired TestSettings settings;
    @Autowired EntityManager em;
    @Autowired WebApplicationContext context;
    @Autowired PlatformTransactionManager transactionManager;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        settings.setDefaultQuestionsCount(20);
        settings.setEnableTimeLimit(true);
        settings.setShowCountdown(true);
        settings.setMultiAnswerMode(MultiAnswerMode.PARTIAL);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        new TransactionTemplate(transactionManager).execute(status -> {
            em.createNativeQuery("""
                    merge into users(id, first_name, last_name, email, password, user_role_id, deleted) key(id)
                    values (1,'Test','One','one@test','x',1,false),
                           (2,'Test','Two','two@test','x',2,false),
                           (7,'Test','Seven','seven@test','x',1,false)
                    """).executeUpdate();
            return null;
        });
    }

    TestDefinition sample(int questionCount, boolean timed) {
        var test = new TestDefinition();
        test.setName("Проверочный тест");
        test.setDescription("Описание проверочного теста");
        test.setTemporaryTest(timed);
        test.setTimeMinutes(timed ? 15 : null);
        test.setOwnerId(2L);
        test.getTesters().add(em.find(User.class, 1L));
        test.getTesters().add(em.find(User.class, 2L));
        test.getTesters().add(em.find(User.class, 7L));
        for (int i = 0; i < questionCount; i++) {
            var question = new Question();
            question.setText("Вопрос " + i);
            question.setMultipleAllowed(true);
            em.persist(question);
            for (int j = 0; j < 3; j++) {
                var answer = new Answer();
                answer.setText("Вариант " + j);
                answer.setCorrect(j < 2);
                answer.setPoints(j < 2 ? BigDecimal.ONE : BigDecimal.ZERO);
                answer.setQuestion(question);
                em.persist(answer);
                question.getAnswers().add(answer);
            }
            test.getQuestions().add(question);
        }
        em.persist(test);
        em.flush();
        return test;
    }

    Set<Long> correct(AttemptQuestion question) {
        return question.getOptions().stream().filter(AttemptOption::isCorrect)
                .map(AttemptOption::getId).collect(Collectors.toSet());
    }

    @Test
    void allTestQuestionsAreIncludedAndSnapshotSurvivesReload() {
        var test = sample(24, false);
        test.getQuestions().forEach(item -> item.setTitle("Заголовок " + item.getText()));
        var attempt = service.start(test.getId(), 1L);
        Long id = attempt.getId();
        assertEquals(24, attempt.getQuestions().size());
        var question = attempt.getQuestions().get(0);
        var optionIds = question.getOptions().stream().map(AttemptOption::getId).toList();
        var questionTexts = attempt.getQuestions().stream().map(AttemptQuestion::getText).toList();
        var titles = attempt.getQuestions().stream().map(AttemptQuestion::getTitle).toList();
        test.getQuestions().forEach(item -> item.setTitle("Изменённый заголовок"));
        service.save(id, 1L, 0, correct(question), 3, false);
        em.flush();
        em.clear();
        var reloaded = service.getAttempt(id, 1L);
        assertEquals(3, reloaded.getCurrentQuestion());
        assertEquals(questionTexts, reloaded.getQuestions().stream().map(AttemptQuestion::getText).toList());
        assertEquals(titles, reloaded.getQuestions().stream().map(AttemptQuestion::getTitle).toList());
        assertEquals(optionIds, reloaded.getQuestions().get(0).getOptions().stream().map(AttemptOption::getId).toList());
        assertEquals(2, reloaded.getQuestions().get(0).getOptions().stream().filter(AttemptOption::isSelected).count());
        assertEquals(id, service.start(test.getId(), 1L).getId());
    }

    @Test
    void exactScoringAndFinishCannotBeOverwritten() {
        var attempt = service.start(sample(2, false).getId(), 1L);
        service.save(attempt.getId(), 1L, 0, correct(attempt.getQuestions().get(0)), 1, false);
        service.save(attempt.getId(), 1L, 1, Set.of(), null, true);
        assertEquals(1, attempt.getCorrectCount());
        assertEquals(50, attempt.getPercentage());
        Instant finishedAt = attempt.getFinishedAt();
        service.save(attempt.getId(), 1L, 1, correct(attempt.getQuestions().get(1)), null, true);
        assertEquals(50, attempt.getPercentage());
        assertEquals(finishedAt, attempt.getFinishedAt());
        assertTrue(attempt.getQuestions().get(1).getOptions().stream().noneMatch(AttemptOption::isSelected));
    }

    @Test
    void pointsDeterminePercentage() {
        var attempt = service.start(sample(1, false).getId(), 1L);
        var selected = Set.of(correct(attempt.getQuestions().get(0)).iterator().next());
        service.save(attempt.getId(), 1L, 0, selected, null, true);
        assertEquals(0, attempt.getCorrectCount());
        assertEquals(50, attempt.getPercentage());
    }

    @Test
    void fractionalPointsAreRoundedOnSaveAndFormattedWithoutTrailingZeros() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        session.setAttribute("fullName", "Администратор");
        mvc.perform(post("/management/questions/save").session(session)
                        .param("text", "Дробные баллы").param("multiple", "true")
                        .param("answerText", "Первый", "Второй", "Третий", "Четвёртый", "Пятый", "Шестой")
                        .param("points", "3.3333", "2.10", "5.0", "3.1", "0.004", "0.005"))
                .andExpect(redirectedUrl("/management?section=questions"));
        em.flush();
        em.clear();

        var question = management.questions(2L, true).stream()
                .filter(item -> "Дробные баллы".equals(item.getText())).findFirst().orElseThrow();
        assertEquals(List.of("3.33", "2.1", "5", "3.1", "0", "0.01"),
                question.getAnswers().stream().map(Answer::getPointsText).toList());
        assertFalse(question.getAnswers().get(4).isCorrect());
        assertTrue(question.getAnswers().get(5).isCorrect());
        mvc.perform(get("/management/questions/" + question.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("step=\"any\"")))
                .andExpect(content().string(containsString("value=\"3.33\"")))
                .andExpect(content().string(containsString("value=\"2.1\"")))
                .andExpect(content().string(containsString("value=\"5\"")));
    }

    @Test
    void fractionalScoringIsExactInAllMultipleAnswerModes() {
        var test = sample(1, false);
        var answers = test.getQuestions().get(0).getAnswers();
        answers.get(0).setPoints(new BigDecimal("0.1"));
        answers.get(1).setPoints(new BigDecimal("0.2"));
        long[] userIds = {1L, 2L, 7L};
        for (var mode : MultiAnswerMode.values()) {
            settings.setMultiAnswerMode(mode);
            long userId = userIds[mode.ordinal()];
            var attempt = service.start(test.getId(), userId);
            var selected = attempt.getQuestions().get(0).getOptions().stream()
                    .filter(option -> option.getPoints().compareTo(new BigDecimal("0.1")) == 0)
                    .findFirst().orElseThrow();
            service.save(attempt.getId(), userId, 0, Set.of(selected.getId()), null, true);
            em.flush();
            em.clear();
            var saved = service.getAttempt(attempt.getId(), userId);
            String expected = switch (mode) {
                case NONE -> "0";
                case PARTIAL -> "0.1";
                case FULL -> "0.3";
            };
            assertEquals(expected, saved.getScoreText());
            assertEquals("0.3", saved.getMaxScoreText());
            assertEquals(mode == MultiAnswerMode.NONE ? 0 : mode == MultiAnswerMode.PARTIAL ? 33.33 : 100,
                    saved.getPercentage());
        }
    }

    @Test
    void singleAnswerFractionalScoresAreShownInResultsAndHistory() throws Exception {
        var test = sample(1, false);
        var question = test.getQuestions().get(0);
        question.setMultipleAllowed(false);
        question.getAnswers().get(0).setPoints(new BigDecimal("2.10"));
        question.getAnswers().get(1).setPoints(new BigDecimal("3.33"));
        var attempt = service.start(test.getId(), 1L);
        var selected = attempt.getQuestions().get(0).getOptions().stream()
                .filter(option -> option.getPoints().compareTo(new BigDecimal("2.1")) == 0)
                .findFirst().orElseThrow();
        service.save(attempt.getId(), 1L, 0, Set.of(selected.getId()), null, true);
        assertEquals("2.1", attempt.getScoreText());
        assertEquals("3.33", attempt.getMaxScoreText());
        assertEquals(63.06, attempt.getPercentage());

        var session = new MockHttpSession();
        session.setAttribute("userId", 1L);
        session.setAttribute("fullName", "Пользователь");
        mvc.perform(get("/attempts/" + attempt.getId() + "/result").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Набрано баллов: 2.1 из 3.33")));
        mvc.perform(get("/profile").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Баллы: 2.1 из 3.33")));
    }

    @Test
    void allMultipleAnswerModesAreCalculatedOnServer() {
        var test = sample(1, false);

        settings.setMultiAnswerMode(MultiAnswerMode.PARTIAL);
        var partial = service.start(test.getId(), 1L);
        var oneCorrect = Set.of(correct(partial.getQuestions().get(0)).iterator().next());
        service.save(partial.getId(), 1L, 0, oneCorrect, null, true);
        assertEquals(50, partial.getPercentage());

        settings.setMultiAnswerMode(MultiAnswerMode.NONE);
        var none = service.start(test.getId(), 2L);
        service.save(none.getId(), 2L, 0,
                Set.of(correct(none.getQuestions().get(0)).iterator().next()), null, true);
        assertEquals(0, none.getPercentage());

        settings.setMultiAnswerMode(MultiAnswerMode.FULL);
        var full = service.start(test.getId(), 7L);
        service.save(full.getId(), 7L, 0,
                Set.of(correct(full.getQuestions().get(0)).iterator().next()), null, true);
        assertEquals(100, full.getPercentage());
        assertEquals(1, full.getCorrectCount());
    }

    @Test
    void closingTestFinishesOpenAttemptAndAddsItToStatistics() {
        var test = sample(1, false);
        var attempt = service.start(test.getId(), 1L);
        service.save(attempt.getId(), 1L, 0, correct(attempt.getQuestions().get(0)), null, false);

        management.closeTest(test.getId(), 2L, true);

        assertTrue(attempt.isFinished());
        assertEquals(100, attempt.getPercentage());
        assertEquals(List.of(attempt), management.statistics(test.getId(), 2L, true));
    }

    @Test
    void administratorHasAccessToTestsOwnedByOtherManagers() {
        var test = sample(1, false);
        test.setOwnerId(7L);
        em.flush();

        assertTrue(management.tests(2L, true).stream().anyMatch(item -> item.getId().equals(test.getId())));
        assertThrows(ResponseStatusException.class,
                () -> management.accessibleTest(test.getId(), 2L, false));
        assertEquals(test.getId(), management.accessibleTest(test.getId(), 2L, true).getId());
        management.closeTest(test.getId(), 2L, true);
        assertTrue(test.isTestClosed());
    }

    @Test
    void testCreationRequiresConfiguredMinimumQuestionCount() {
        var source = sample(2, false);
        var questionIds = source.getQuestions().stream().map(Question::getId).toList();

        var error = assertThrows(ResponseStatusException.class, () -> management.createTest(
                "Слишком короткий тест", "Описание", false, null,
                questionIds, List.of(1L), 2L, true));

        assertEquals("В тесте должно быть не меньше 20 вопросов", error.getReason());
    }

    @Test
    void timeLimitAcceptsOnlyIntegerInAllowedRange() {
        var source = sample(20, false);
        var questionIds = source.getQuestions().stream().map(Question::getId).toList();

        assertThrows(ResponseStatusException.class, () -> management.createTest(
                "Тест", "Описание", true, "1e3", questionIds, List.of(1L), 2L, true));
        assertThrows(ResponseStatusException.class, () -> management.createTest(
                "Тест", "Описание", true, "1000001", questionIds, List.of(1L), 2L, true));
    }

    @Test
    void globalTimeLimitSettingOnlyDisablesCreatingNewTimedTests() throws Exception {
        settings.setEnableTimeLimit(false);
        var test = sample(1, true);
        assertEquals(15, service.timeLimit(test));
        assertNotNull(service.start(test.getId(), 1L).getDeadline());

        var source = sample(20, false);
        var questionIds = source.getQuestions().stream().map(Question::getId).toList();
        var error = assertThrows(ResponseStatusException.class, () -> management.createTest(
                "Временный тест", "Описание", true, "15",
                questionIds, List.of(1L), 2L, true));
        assertEquals("Создание временных тестов отключено", error.getReason());

        var session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        session.setAttribute("fullName", "Администратор");
        session.setAttribute("roleId", 2L);
        mvc.perform(get("/management/tests/new").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"timed\""))));
    }

    @Test
    void testFormUsesQuestionRowsAndKeepsRegularOptionCheckboxes() throws Exception {
        var test = sample(20, false);
        var session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        session.setAttribute("fullName", "Администратор");
        session.setAttribute("roleId", 2L);

        mvc.perform(get("/management/tests/new").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Добавить вопросы")))
                .andExpect(content().string(containsString("id=\"timed\"")))
                .andExpect(content().string(containsString("id=\"timeField\" hidden")))
                .andExpect(content().string(containsString("name=\"userIds\"")));

        mvc.perform(get("/management/tests/" + test.getId() + "/content").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("test-content-question")))
                .andExpect(content().string(containsString("answer-points")));
    }

    @Test
    void statisticsCanBeExportedToCsvAndExcel() throws Exception {
        var test = sample(1, false);
        var attempt = service.start(test.getId(), 1L);
        service.save(attempt.getId(), 1L, 0,
                correct(attempt.getQuestions().get(0)), null, true);
        management.closeTest(test.getId(), 2L, true);

        var session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        session.setAttribute("fullName", "Администратор");
        session.setAttribute("roleId", 2L);

        mvc.perform(get("/management/tests/" + test.getId() + "/statistics").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Выгрузить</button>")))
                .andExpect(content().string(containsString("id=\"exportDialog\"")))
                .andExpect(content().string(containsString("format=csv")))
                .andExpect(content().string(containsString("format=xlsx")));

        var csvResult = mvc.perform(get("/management/tests/" + test.getId() +
                        "/statistics/export?format=csv").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andReturn();
        var csv = new String(csvResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("Пользователь"));
        assertTrue(csv.contains("One Test"));
        assertTrue(csv.contains("Проверочный тест"));

        var xlsxResult = mvc.perform(get("/management/tests/" + test.getId() +
                        "/statistics/export?format=xlsx").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andReturn();
        try (var workbook = WorkbookFactory.create(
                new ByteArrayInputStream(xlsxResult.getResponse().getContentAsByteArray()))) {
            assertEquals(1, workbook.getNumberOfSheets());
            var sheet = workbook.getSheet("Результаты");
            assertEquals("Пользователь", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("One Test", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Проверочный тест", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(1, sheet.getRow(1).getCell(2).getNumericCellValue());
            assertTrue(sheet.getRow(1).getCell(5).getDateCellValue().getTime() > 0);
        }
    }

    @Test
    void extendedStatisticsExportSavedAnswersWithoutAttemptColumn() throws Exception {
        var test = sample(3, false);
        var attempt = service.start(test.getId(), 1L);
        var question = attempt.getQuestions().get(0);
        question.setText("Вопрос; \"цитата\"\nСледующая строка");
        Long selected = correct(question).iterator().next();
        service.save(attempt.getId(), 1L, 0, Set.of(selected), null, false);
        var session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        String path = "/management/tests/" + test.getId() + "/statistics/export";

        var csvResult = mvc.perform(get(path).param("format", "csv").param("detail", "extended").session(session))
                .andExpect(status().isOk()).andReturn();
        var csv = new String(csvResult.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"));
        var format = org.apache.commons.csv.CSVFormat.DEFAULT.builder().setDelimiter(';').get();
        try (var parser = format.parse(new java.io.StringReader(csv.substring(1)))) {
            var rows = parser.getRecords();
            assertEquals(4, rows.size());
            assertEquals(5, rows.get(0).size());
            assertFalse(rows.get(0).toList().contains("Попытка"));
            assertEquals("One Test", rows.get(1).get(0));
            assertEquals("1", rows.get(1).get(1));
            assertEquals(question.getText(), rows.get(1).get(2));
            String selectedText = question.getOptions().stream().filter(option -> option.getId().equals(selected))
                    .findFirst().orElseThrow().getText();
            assertEquals(selectedText, rows.get(1).get(3));
            assertEquals(2, rows.get(1).get(4).split("\n").length);
            assertEquals("Нет ответа", rows.get(2).get(3));
        }

        var xlsxResult = mvc.perform(get(path).param("format", "xlsx").param("detail", "extended").session(session))
                .andExpect(status().isOk()).andReturn();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(xlsxResult.getResponse().getContentAsByteArray()))) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertNotNull(workbook.getSheet("Результаты"));
            var sheet = workbook.getSheet("Ответы");
            assertEquals(3, sheet.getLastRowNum());
            assertEquals(5, sheet.getRow(0).getLastCellNum());
            assertEquals(1, sheet.getRow(1).getCell(1).getNumericCellValue());
            assertEquals(question.getText(), sheet.getRow(1).getCell(2).getStringCellValue());
            assertTrue(sheet.getRow(1).getCell(2).getCellStyle().getWrapText());
            assertTrue(sheet.getRow(1).getHeightInPoints() >= 30);
            assertEquals("Нет ответа", sheet.getRow(3).getCell(3).getStringCellValue());
        }
        assertFalse(attempt.isFinished());
        mvc.perform(get(path).param("format", "csv").param("detail", "unknown").session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void participantManagementRevokesAccessAndAllowsEmptyAssignments() throws Exception {
        var test = sample(3, true);
        var attempt = service.start(test.getId(), 1L);
        var session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        mvc.perform(get("/management/tests/" + test.getId() + "/manage").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Не начал прохождение")))
                .andExpect(content().string(containsString("data-countdown")));

        management.saveParticipants(test.getId(), List.of(2L), 2L, false);
        var denied = assertThrows(ResponseStatusException.class, () -> service.getAttempt(attempt.getId(), 1L));
        assertEquals(409, denied.getStatusCode().value());
        assertThrows(ResponseStatusException.class,
                () -> service.save(attempt.getId(), 1L, 0, correct(attempt.getQuestions().get(0)), null, false));
        assertTrue(service.listTests(1L).stream().noneMatch(item -> item.getId().equals(test.getId())));
        assertFalse(attempt.isFinished());
        assertThrows(ResponseStatusException.class,
                () -> management.saveParticipants(test.getId(), List.of(1L), 7L, false));

        management.saveParticipants(test.getId(), null, 2L, true);
        assertTrue(test.getTesters().isEmpty());
        settings.setDefaultQuestionsCount(3);
        var ids = test.getQuestions().stream().map(question -> question.getId()).toList();
        var empty = management.createTest("Без участников", "", false, "", ids, null, 2L, true);
        assertTrue(empty.getTesters().isEmpty());

        management.closeTest(test.getId(), 2L, true);
        assertThrows(ResponseStatusException.class,
                () -> management.saveParticipants(test.getId(), List.of(1L), 2L, true));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedTestImportRollsBackNewQuestions() {
        settings.setDefaultQuestionsCount(3);
        int initialCount = management.questions(2L, false).size();
        var content = new StringBuilder("Импорт;Описание;;\n");
        for (int index = 0; index < 3; index++) {
            content.append("Новый вопрос ").append(index).append(";Да;1;\n;Нет;;\n");
        }
        var file = new MockMultipartFile("file", "test.csv", "text/csv",
                content.toString().getBytes(StandardCharsets.UTF_8));
        var importer = new ru.morkamo.enterprisetester.service.QuestionImportService();
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            assertThrows(ResponseStatusException.class, () -> transaction.execute(status ->
                    management.importTest(importer.parseTest(file), List.of(-1L), 2L, true)));
            assertEquals(initialCount, management.questions(2L, false).size());
        } finally {
            settings.setDefaultQuestionsCount(20);
        }
    }

    @Test
    void uploadedQuestionImageIsKeptInAttemptAfterQuestionChanges() throws Exception {
        var test = sample(1, false);
        var question = test.getQuestions().get(0);
        var picture = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(picture, "png", bytes);
        var upload = new MockMultipartFile("imagesToAdd", "question.png", "image/png", bytes.toByteArray());
        var second = new MockMultipartFile("imagesToAdd", "second.png", "image/png", bytes.toByteArray());
        var session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        session.setAttribute("roleId", 2L);

        mvc.perform(multipart("/management/questions/save").file(upload).file(second)
                        .param("id", question.getId().toString())
                        .param("text", question.getText())
                        .param("answerText", "Да", "Нет")
                        .param("points", "1", "0")
                        .session(session))
                .andExpect(redirectedUrl("/management?section=questions"));
        assertEquals(2, question.getImageNames().size());

        var attempt = service.start(test.getId(), 1L);
        var player = new MockHttpSession();
        player.setAttribute("userId", 1L);
        mvc.perform(get("/attempts/" + attempt.getId()).session(player))
                .andExpect(content().string(containsString("/questions/0/images/0")))
                .andExpect(content().string(containsString("/questions/0/images/1")));
        mvc.perform(get("/attempts/" + attempt.getId() + "/questions/0/images/0").session(player))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        mvc.perform(get("/attempts/" + attempt.getId() + "/questions/0/images/1").session(player))
                .andExpect(status().isOk());
        mvc.perform(get("/attempts/" + attempt.getId() + "/questions/0/images/0").session(session))
                .andExpect(status().isNotFound());

        question.getImageNames().clear();
        assertEquals(2, attempt.getQuestions().get(0).getImageNames().size());
        mvc.perform(get("/attempts/" + attempt.getId() + "/questions/0/images/0").session(player))
                .andExpect(status().isOk());
    }

    @Test
    void invalidImageIsRejected() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        session.setAttribute("roleId", 2L);
        var fake = new MockMultipartFile("imagesToAdd", "fake.png", "image/png", "not an image".getBytes());
        mvc.perform(multipart("/management/questions/save").file(fake)
                        .param("text", "Вопрос")
                        .param("answerText", "Да", "Нет")
                        .param("points", "1", "0")
                        .session(session))
                .andExpect(redirectedUrl("/management/questions/new"))
                .andExpect(flash().attribute("error", "Файл не является изображением"));
    }

    @Test
    void newQuestionAndAnswersAreSavedTogether() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        mvc.perform(multipart("/management/questions/save")
                        .param("text", "Новый вопрос")
                        .param("answerText", "Верно", "Неверно")
                        .param("points", "1", "0")
                        .session(session))
                .andExpect(redirectedUrl("/management?section=questions"));
        em.flush();
        em.clear();
        var saved = em.createQuery("select q from Question q where q.text = :text", Question.class)
                .setParameter("text", "Новый вопрос").getSingleResult();
        assertEquals(2, saved.getAnswers().size());
        assertTrue(saved.getAnswers().stream().allMatch(answer -> answer.getQuestion().getId().equals(saved.getId())));
    }

    @Test
    void moreThanTenImagesAreRejected() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        var request = multipart("/management/questions/save")
                .param("text", "Вопрос")
                .param("answerText", "Да", "Нет")
                .param("points", "1", "0")
                .session(session);
        for (int index = 0; index < 11; index++) {
            request.file(new MockMultipartFile("imagesToAdd", "image" + index + ".png",
                    "image/png", new byte[]{1}));
        }
        mvc.perform(request)
                .andExpect(redirectedUrl("/management/questions/new"))
                .andExpect(flash().attribute("error", "К вопросу можно прикрепить не больше 10 изображений"));
    }

    @Test
    void deletingUserKeepsFinishedResultsAndBlocksLogin() throws Exception {
        var test = sample(1, false);
        var attempt = service.start(test.getId(), 1L);
        service.save(attempt.getId(), 1L, 0, correct(attempt.getQuestions().get(0)), null, false);
        management.deleteUser(1L, 2L);
        management.closeTest(test.getId(), 2L, true);

        assertTrue(em.find(User.class, 1L).isDeleted());
        assertTrue(attempt.isFinished());
        assertEquals(1, management.statistics(test.getId(), 2L, true).size());
        assertTrue(management.users().stream().noneMatch(user -> user.getId().equals(1L)));

        var player = new MockHttpSession();
        player.setAttribute("userId", 1L);
        player.setAttribute("fullName", "Test One");
        mvc.perform(get("/mainpage").session(player)).andExpect(redirectedUrl("/"));
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"email\":\"one@test\",\"password\":\"x\"}"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void deletingManagerTransfersTheirContentToAdministrator() {
        var test = sample(1, false);
        test.setOwnerId(7L);
        var questionId = test.getQuestions().get(0).getId();
        test.getQuestions().get(0).setOwnerId(7L);
        em.flush();

        management.deleteUser(7L, 2L);
        em.flush();
        em.clear();

        assertEquals(2L, management.accessibleTest(test.getId(), 2L, true).getOwnerId());
        assertEquals(2L, management.question(questionId, 2L, false).getOwnerId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void fiftyUsersCanOpenMainPageAtOnce() throws Exception {
        new TransactionTemplate(transactionManager).execute(status -> {
            for (int id = 100; id < 150; id++) {
                em.createNativeQuery("insert into users(id, first_name, last_name, email, password, user_role_id, deleted) "
                                + "values (:id, 'Load', 'Tester', :email, 'x', 1, false)")
                        .setParameter("id", id).setParameter("email", "load" + id + "@test")
                        .executeUpdate();
            }
            return null;
        });
        var executor = Executors.newFixedThreadPool(50);
        var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> requests = new ArrayList<>();
            for (long id = 100; id < 150; id++) {
                long userId = id;
                requests.add(executor.submit(() -> {
                    start.await();
                    var session = new MockHttpSession();
                    session.setAttribute("userId", userId);
                    session.setAttribute("fullName", "Load Tester");
                    return mvc.perform(get("/mainpage").session(session)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var request : requests) assertEquals(200, request.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void zeroPointOptionDoesNotAddPoints() {
        var attempt = service.start(sample(1, false).getId(), 1L);
        var all = attempt.getQuestions().get(0).getOptions().stream().map(AttemptOption::getId).collect(Collectors.toSet());
        service.save(attempt.getId(), 1L, 0, all, null, true);
        assertEquals(100, attempt.getPercentage());
        assertEquals(0, attempt.getCorrectCount());
    }

    @Test
    void expiredAttemptIgnoresLateAnswers() {
        var attempt = service.start(sample(1, true).getId(), 1L);
        assertEquals(900, attempt.getDeadline().getEpochSecond() - attempt.getStartedAt().getEpochSecond());
        attempt.setDeadline(Instant.now().minusSeconds(1));
        service.save(attempt.getId(), 1L, 0, correct(attempt.getQuestions().get(0)), null, true);
        assertTrue(attempt.isFinished());
        assertEquals(attempt.getDeadline(), attempt.getFinishedAt());
        assertEquals(0, attempt.getPercentage());
    }

    @Test
    void expiredAttemptCannotBeStartedAgain() {
        var test = sample(1, true);
        var attempt = service.start(test.getId(), 1L);
        attempt.setDeadline(Instant.now().minusSeconds(1));
        em.flush();
        var error = assertThrows(ResponseStatusException.class,
                () -> service.start(test.getId(), 1L));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(attempt.isFinished());
        assertEquals(1, service.getAttempt(attempt.getId(), 1L).getQuestions().size());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousStartsReuseOneAttempt() throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        Long testId = transaction.execute(status -> sample(1, false).getId());
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return service.start(testId, 7L).getId(); });
            var second = executor.submit(() -> { start.await(); return service.start(testId, 7L).getId(); });
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1L, transaction.execute(status ->
                    em.createQuery("select count(a) from TestAttempt a where a.testId = :testId and a.userId = 7", Long.class)
                            .setParameter("testId", testId).getSingleResult()).longValue());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeLimitBelongsToTest() {
        var test = sample(1, true);
        assertEquals(15, service.timeLimit(test));
        assertNotNull(service.start(test.getId(), 1L).getDeadline());
    }

    @Test
    void wrongOwnerAndForeignAnswersAreRejected() {
        var attempt = service.start(sample(1, false).getId(), 1L);
        assertThrows(ResponseStatusException.class, () -> service.getAttempt(attempt.getId(), 2L));
        assertThrows(ResponseStatusException.class,
                () -> service.save(attempt.getId(), 1L, 0, Set.of(-1L), null, false));
        assertThrows(ResponseStatusException.class,
                () -> service.save(attempt.getId(), 1L, 9, Set.of(), null, false));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentLateSaveCannotChangeFinishedAttempt() throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        var attempt = transaction.execute(status -> service.start(sample(1, false).getId(), 7L));
        var ids = correct(attempt.getQuestions().get(0));
        var finished = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var finishing = executor.submit(() -> transaction.execute(status -> {
                service.save(attempt.getId(), 7L, 0, Set.of(), null, true);
                finished.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            var saving = executor.submit(() -> service.save(attempt.getId(), 7L, 0, ids, null, false));
            release.countDown();
            finishing.get(5, TimeUnit.SECONDS);
            assertTrue(saving.get(5, TimeUnit.SECONDS).isFinished());
            var result = service.getAttempt(attempt.getId(), 7L);
            assertEquals(0, result.getPercentage());
            assertTrue(result.getQuestions().get(0).getOptions().stream().noneMatch(AttemptOption::isSelected));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void pagesRenderAndFinishedAttemptRedirectsToResult() throws Exception {
        var test = sample(1, true);
        var session = new MockHttpSession();
        session.setAttribute("userId", 1L);
        session.setAttribute("fullName", "Иван Петров");
        mvc.perform(get("/tests/" + test.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Время для прохождения: 15 минут.")));
        var attempt = service.start(test.getId(), 1L);
        mvc.perform(get("/mainpage").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Продолжить"))));
        mvc.perform(get("/tests/" + test.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Продолжить тест")));
        mvc.perform(get("/attempts/" + attempt.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Завершить")))
                .andExpect(content().string(not(containsString("is_correct"))));
        var ids = correct(attempt.getQuestions().get(0)).stream().map(Object::toString).toArray(String[]::new);
        mvc.perform(post("/attempts/" + attempt.getId() + "/navigate").session(session)
                        .param("questionIndex", "0").param("selected", ids).param("complete", "true"))
                .andExpect(redirectedUrl("/attempts/" + attempt.getId() + "/result"));
        mvc.perform(get("/attempts/" + attempt.getId()).session(session))
                .andExpect(redirectedUrl("/attempts/" + attempt.getId() + "/result"));
        mvc.perform(get("/attempts/" + attempt.getId() + "/result").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Верно отвечено: 1")))
                .andExpect(content().string(containsString("Результат: 100.0%")));
        mvc.perform(post("/attempts/" + attempt.getId() + "/answer").session(session)
                        .param("questionIndex", "0"))
                .andExpect(content().string("true"));
        assertEquals(100, attempt.getPercentage());
    }
}
