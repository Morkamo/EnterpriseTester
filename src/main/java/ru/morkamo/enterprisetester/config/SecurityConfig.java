package ru.morkamo.enterprisetester.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import ru.morkamo.enterprisetester.repository.UserRepository;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository users) {
        return email -> {
            var account = users.findByEmail(email)
                    .filter(user -> !user.isDeleted())
                    .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
            String role = switch (account.getUserRole() == null ? 1 : account.getUserRole().intValue()) {
                case 2 -> "ADMIN";
                case 3 -> "MANAGER";
                default -> "USER";
            };
            return User.withUsername(account.getEmail())
                    .password(account.getPassword()).roles(role).build();
        };
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService details, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts) throws Exception {
        http.securityContext(security -> security.securityContextRepository(contexts));
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/api/auth/login",
                        "/api/auth/csrf", "/error").permitAll()
                .requestMatchers("/management/users/**").hasRole("ADMIN")
                .requestMatchers("/management/**").hasAnyRole("ADMIN", "MANAGER")
                .anyRequest().authenticated());
        http.formLogin(login -> login.disable());
        http.httpBasic(basic -> basic.disable());
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> {
                    if (request.getRequestURI().startsWith("/api/")) response.sendError(HttpStatus.UNAUTHORIZED.value());
                    else response.sendRedirect("/");
                })
                .accessDeniedHandler((request, response, error) -> response.sendError(HttpStatus.FORBIDDEN.value())));
        http.logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/").invalidateHttpSession(true));
        return http.build();
    }
}
