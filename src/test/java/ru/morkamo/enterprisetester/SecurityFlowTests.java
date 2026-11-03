package ru.morkamo.enterprisetester;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import ru.morkamo.enterprisetester.model.User;
import ru.morkamo.enterprisetester.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "testing.upload-dir=target/test-security-images"
})
@Transactional
class SecurityFlowTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ru.morkamo.enterprisetester.service.ManagementService management;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var user = new User("Иван", "Петров", "user@test.local", passwords.encode("secret"), 1L);
        user.setUsername("ivan.petrov");
        users.save(user);
        users.save(new User("Анна", "Иванова", "admin@test.local", passwords.encode("secret"), 2L));
        users.save(new User("Мария", "Сидорова", "manager@test.local", passwords.encode("secret"), 3L));
    }

    private String csrf(MockHttpSession session) throws Exception {
        String json = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\\\"token\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private MockHttpSession login(String email) throws Exception {
        return login("email", email);
    }

    private MockHttpSession login(String field, String value) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", csrf(session))
                        .contentType("application/json")
                        .content("{\"" + field + "\":\"" + value + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        return session;
    }

    @Test
    void usernameLoginIgnoresCaseAndKeepsAuthenticatedSession() throws Exception {
        var session = login("username", "IVAN.PETROV");
        assertEquals(users.findByEmail("user@test.local").orElseThrow().getId(), session.getAttribute("userId"));
        mvc.perform(get("/mainpage").session(session)).andExpect(status().isOk());
        mvc.perform(get("/profile").session(session)).andExpect(status().isOk());
        mvc.perform(get("/management").session(session)).andExpect(status().isForbidden());
        login("user@test.local");
    }

    @Test
    void usernameLoginRejectsWrongFieldPasswordAndDeletedAccounts() throws Exception {
        for (String body : new String[]{
                "{\"username\":\"ivan.petrov\",\"password\":\"wrong\"}",
                "{\"email\":\"ivan.petrov\",\"password\":\"secret\"}",
                "{\"username\":\"user@test.local\",\"password\":\"secret\"}",
                "{\"username\":\"' OR '1'='1\",\"password\":\"secret\"}",
                "{\"email\":\"user@test.local\",\"username\":\"ivan.petrov\",\"password\":\"secret\"}"
        }) {
            var session = new MockHttpSession();
            mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", csrf(session))
                            .contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));
        }
        users.findByEmail("user@test.local").orElseThrow().setDeleted(true);
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", csrf(session))
                        .contentType("application/json")
                        .content("{\"username\":\"ivan.petrov\",\"password\":\"secret\"}"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void usernamesCanBeAssignedChangedAndCleared() throws Exception {
        var admin = login("admin@test.local");
        mvc.perform(post("/management/users/save").session(admin).header("X-CSRF-TOKEN", csrf(admin))
                        .param("firstName", "Новый").param("lastName", "Сотрудник")
                        .param("email", "new@test.local").param("username", "New.User")
                        .param("password", "fresh-secret").param("roleId", "1"))
                .andExpect(status().is3xxRedirection());
        var created = users.findByEmail("new@test.local").orElseThrow();
        assertEquals("new.user", created.getUsername());
        mvc.perform(get("/management?section=users").session(admin)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"new.user\"")));
        management.saveUser(created.getId(), "Новый", "Сотрудник", "new@test.local", "changed_name", "", 1L);
        assertEquals("changed_name", created.getUsername());
        management.saveUser(created.getId(), "Новый", "Сотрудник", "new@test.local", "", "", 1L);
        assertNull(created.getUsername());
        assertTrue(passwords.matches("fresh-secret", created.getPassword()));
    }

    @Test
    void invalidAndDuplicateUsernamesAreRejected() {
        for (String name : new String[]{"ab", "a".repeat(33), "two names", "user@test.local", "<script>"}) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> management.saveUser(null, "Имя", "Фамилия", "new@test.local", name, "secret", 1L));
        }
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> management.saveUser(null, "Имя", "Фамилия", "new@test.local", "IVAN.PETROV", "secret", 1L));
        assertEquals("Имя пользователя уже используется", error.getReason());
    }

    @Test
    void loginRolesCsrfAndLogoutAreEnforced() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/mainpage")).andExpect(status().is3xxRedirection());
        var forged = new MockHttpSession();
        forged.setAttribute("userId", 1L);
        mvc.perform(get("/mainpage").session(forged)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/auth/login").session(session).contentType("application/json")
                        .content("{\"email\":\"user@test.local\",\"password\":\"secret\"}"))
                .andExpect(status().isForbidden());
        session = login("user@test.local");
        mvc.perform(get("/mainpage").session(session)).andExpect(status().isOk());
        mvc.perform(get("/management").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/profile").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).header("X-CSRF-TOKEN", csrf(session)))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/mainpage").session(session)).andExpect(status().is3xxRedirection());
    }

    @Test
    void adminAndManagerPermissionsAreSeparated() throws Exception {
        var manager = login("manager@test.local");
        mvc.perform(get("/management").session(manager)).andExpect(status().isOk());
        mvc.perform(post("/management/users/save").session(manager)
                        .header("X-CSRF-TOKEN", csrf(manager)))
                .andExpect(status().isForbidden());

        var admin = login("admin@test.local");
        mvc.perform(get("/management?section=users").session(admin)).andExpect(status().isOk());
        mvc.perform(post("/management/users/save").session(admin).param("firstName", "Новый"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/management/users/save").session(admin)
                        .header("X-CSRF-TOKEN", csrf(admin))
                        .param("firstName", "Новый").param("lastName", "Сотрудник")
                        .param("email", "new@test.local").param("password", "fresh-secret")
                        .param("roleId", "1"))
                .andExpect(status().is3xxRedirection());
        var created = users.findByEmail("new@test.local").orElseThrow();
        assertNotEquals("fresh-secret", created.getPassword());
        assertTrue(passwords.matches("fresh-secret", created.getPassword()));
    }

    @Test
    void csvImportRequiresPermissionsCsrfAndExplicitConfirmation() throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "questions.csv", "text/csv",
                ("<script>alert(1)</script>;Да;0,6;1\n;Нет;;\n"
                        + "Второй;Первый;1;\n;Второй;;\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var user = login("user@test.local");
        for (String endpoint : new String[]{"/management/questions/import/preview", "/management/questions/import"}) {
            mvc.perform(multipart(endpoint).file(file).session(user).header("X-CSRF-TOKEN", csrf(user)))
                    .andExpect(status().isForbidden());
        }
        var manager = login("manager@test.local");
        Long ownerId = (Long) manager.getAttribute("userId");
        int initialCount = management.questions(ownerId, false).size();
        mvc.perform(multipart("/management/questions/import/preview").file(file).session(manager))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/management/questions/import/preview").file(file).session(manager)
                        .header("X-CSRF-TOKEN", csrf(manager)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>"))));
        assertEquals(initialCount, management.questions(ownerId, false).size());
        mvc.perform(multipart("/management/questions/import").file(file).session(manager)
                        .header("X-CSRF-TOKEN", csrf(manager)))
                .andExpect(redirectedUrl("/management?section=questions"))
                .andExpect(flash().attribute("success", "Успешный импорт"));
        var imported = management.questions(ownerId, false);
        assertEquals(initialCount + 2, imported.size());
        assertTrue(imported.stream().allMatch(question -> ownerId.equals(question.getOwnerId())));
        var question = imported.stream().filter(item -> item.isMultipleAllowed()).findFirst().orElseThrow();
        assertEquals("<script>alert(1)</script>", question.getText());
        assertEquals("0.6", question.getAnswers().get(0).getPointsText());
        mvc.perform(get("/management?section=questions").session(manager)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Импорт вопросов")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
        mvc.perform(get("/management/questions/" + question.getId()).session(manager)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"text\"")));

        var invalid = new org.springframework.mock.web.MockMultipartFile("file", "questions.csv", "text/csv",
                "Третий;Да;1;\n;Нет;;\nЧетвёртый;Да;ошибка;\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/management/questions/import/preview").file(invalid).session(manager)
                        .header("X-CSRF-TOKEN", csrf(manager)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Строка 3:")));
        mvc.perform(multipart("/management/questions/import").file(invalid).session(manager)
                        .header("X-CSRF-TOKEN", csrf(manager)))
                .andExpect(flash().attributeExists("error"));
        assertEquals(initialCount + 2, management.questions(ownerId, false).size());
    }

    @Test
    void wrongPasswordAndDeletedUserCannotLogIn() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", csrf(session))
                        .contentType("application/json")
                        .content("{\"email\":\"user@test.local\",\"password\":\"wrong\"}"))
                .andExpect(jsonPath("$.success").value(false));
        var account = users.findByEmail("user@test.local").orElseThrow();
        account.setDeleted(true);
        assertTrue(passwords.matches("secret", account.getPassword()));
        session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", csrf(session))
                        .contentType("application/json")
                        .content("{\"email\":\"user@test.local\",\"password\":\"secret\"}"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void testImportPreviewsWithoutSavingAndReusesOnlyOwnersQuestions() throws Exception {
        var manager = login("manager@test.local");
        Long ownerId = (Long) manager.getAttribute("userId");
        var content = new StringBuilder("Тест импорта;Описание;10;\n");
        for (int index = 1; index <= 3; index++) {
            content.append("Вопрос ").append(index).append(";Да;1;\n;Нет;;\n");
        }
        var file = new org.springframework.mock.web.MockMultipartFile("file", "test.csv", "text/csv",
                content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/management/tests/import/preview").file(file).session(manager))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/management/tests/import/preview").file(file).session(manager)
                        .header("X-CSRF-TOKEN", csrf(manager)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Тест импорта")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"userIds\"")));
        assertTrue(management.questions(ownerId, false).isEmpty());
        assertTrue(management.tests(ownerId, false).isEmpty());

        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(multipart("/management/tests/import").file(file).session(manager)
                            .param("userIds", ownerId.toString()).header("X-CSRF-TOKEN", csrf(manager)))
                    .andExpect(redirectedUrl("/management?section=tests"))
                    .andExpect(flash().attribute("success", "Успешный импорт"));
        }
        assertEquals(3, management.questions(ownerId, false).size());
        var tests = management.tests(ownerId, false);
        assertEquals(2, tests.size());
        var firstIds = tests.get(0).getQuestions().stream().map(question -> question.getId()).sorted().toList();
        var secondIds = tests.get(1).getQuestions().stream().map(question -> question.getId()).sorted().toList();
        assertEquals(firstIds, secondIds);
        assertEquals(3, tests.get(0).getQuestions().size());
        assertEquals(10, tests.get(0).getTimeMinutes());

        mvc.perform(multipart("/management/tests/import").file(file).session(manager)
                        .header("X-CSRF-TOKEN", csrf(manager)))
                .andExpect(flash().attribute("success", "Успешный импорт"));
        assertTrue(management.tests(ownerId, false).get(0).getTesters().isEmpty());
        String managePath = "/management/tests/" + tests.get(0).getId();
        mvc.perform(get(managePath + "/manage").session(manager)).andExpect(status().isOk());
        mvc.perform(post(managePath + "/participants").session(manager)).andExpect(status().isForbidden());
        mvc.perform(post(managePath + "/participants").session(manager).header("X-CSRF-TOKEN", csrf(manager)))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("success", "Сохранено"));
        assertTrue(tests.get(0).getTesters().isEmpty());

        var importer = new ru.morkamo.enterprisetester.service.QuestionImportService();
        var reordered = new org.springframework.mock.web.MockMultipartFile("file", "q.csv", "text/csv",
                "Вопрос 1;Нет;0;\n;Да;1.00;\nВопрос 1;Да;1;\n;Нет;0;\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var resolved = management.importQuestions(importer.parse(reordered), ownerId);
        assertEquals(1, resolved.size());
        assertEquals(3, management.questions(ownerId, false).size());

        var changed = new org.springframework.mock.web.MockMultipartFile("file", "q.csv", "text/csv",
                "Вопрос 1;Да;2;\n;Нет;;\nВопрос 1;Да;1;1\n;Нет;;\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2, management.importQuestions(importer.parse(changed), ownerId).size());
        assertEquals(5, management.questions(ownerId, false).size());

        resolved.get(0).getImageNames().add("existing.png");
        var withoutImage = management.importQuestions(importer.parse(reordered), ownerId);
        assertNotEquals(resolved.get(0).getId(), withoutImage.get(0).getId());
        assertEquals(6, management.questions(ownerId, false).size());

        var admin = login("admin@test.local");
        Long adminId = (Long) admin.getAttribute("userId");
        var otherOwner = management.importQuestions(importer.parse(reordered), adminId);
        assertNotEquals(resolved.get(0).getId(), otherOwner.get(0).getId());
        mvc.perform(get("/management?section=questions").session(admin)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("[От: manager@test.local]")));
        mvc.perform(get("/management/tests/new").session(admin)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("[От: admin@test.local]"))));

        var account = users.findById(ownerId).orElseThrow();
        account.setUsername("preferred.login");
        assertEquals("[От: preferred.login]", management.questionOwners().get(ownerId));
        account.setUsername(null);
        account.setEmail(null);
        assertEquals("[null]", management.questionOwners().get(ownerId));
    }
}
