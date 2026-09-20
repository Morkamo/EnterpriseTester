package ru.morkamo.enterprisetester.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.morkamo.enterprisetester.repository.UserRepository;

@Component
public class PasswordMigration implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public PasswordMigration(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        for (var user : users.findAll()) {
            String password = user.getPassword();
            if (password != null && !password.matches("^\\$2[aby]\\$\\d{2}\\$.*")) {
                user.setPassword(encoder.encode(password));
            }
        }
    }
}
