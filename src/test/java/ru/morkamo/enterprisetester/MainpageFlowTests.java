package ru.morkamo.enterprisetester;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import ru.morkamo.enterprisetester.controller.AuthController;
import ru.morkamo.enterprisetester.controller.PageController;
import ru.morkamo.enterprisetester.model.User;
import ru.morkamo.enterprisetester.repository.UserRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.Optional;
import java.util.List;
import ru.morkamo.enterprisetester.service.TestService;
import ru.morkamo.enterprisetester.model.TestDefinition;
import ru.morkamo.enterprisetester.repository.TestAttemptRepository;
import ru.morkamo.enterprisetester.service.SessionService;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MainpageFlowTests {
    @Test
    void loginShowsDatabaseNameAndKeepsItOnReload() throws Exception {
        var repository = mock(UserRepository.class);
        var user = new User("Иван", "Петров", "test@example.com", "test-password", 1L);
        user.setId(42L);
        when(repository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(repository.findById(42L)).thenReturn(Optional.of(user));

        var templates = new ClassLoaderTemplateResolver();
        templates.setPrefix("templates/");
        templates.setSuffix(".html");
        templates.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templates);
        var views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8");

        var tests = mock(TestService.class);
        var javaTest = new TestDefinition();
        javaTest.setId(1L);
        javaTest.setName("Основы Java");
        var springTest = new TestDefinition();
        springTest.setId(2L);
        springTest.setName("Основы Spring Boot");
        when(tests.listTests(42L)).thenReturn(List.of(javaTest, springTest));
        var attempts = mock(TestAttemptRepository.class);
        var authenticator = mock(AuthenticationManager.class);
        when(authenticator.authenticate(any())).thenAnswer(invocation -> {
            var token = (UsernamePasswordAuthenticationToken) invocation.getArgument(0);
            if (!"test-password".equals(token.getCredentials())) throw new BadCredentialsException("Неверный пароль");
            return UsernamePasswordAuthenticationToken.authenticated("test@example.com", null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
        });
        var mvc = MockMvcBuilders.standaloneSetup(
                new AuthController(authenticator, new HttpSessionSecurityContextRepository(), repository),
                new PageController(tests, attempts, new SessionService(repository)))
                .setViewResolvers(views).build();
        var session = new MockHttpSession();

        mvc.perform(post("/api/auth/login").session(session)
                        .contentType("application/json")
                        .content("""
                                {"email":"test@example.com","password":"test-password"}
                                """))
                .andExpect(jsonPath("$.success").value(true));

        for (int visit = 0; visit < 2; visit++) {
            mvc.perform(get("/mainpage").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Иван Петров")))
                    .andExpect(content().string(containsString("Основы Java")))
                    .andExpect(content().string(containsString("Основы Spring Boot")));
        }

        mvc.perform(post("/api/auth/login").session(session)
                        .contentType("application/json")
                        .content("""
                                {"email":"test@example.com","password":"wrong"}
                                """))
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/mainpage")).andExpect(redirectedUrl("/"));
        mvc.perform(get("/mainpage"))
                .andExpect(redirectedUrl("/"));
    }
}
