package ru.morkamo.enterprisetester.service;

import org.springframework.stereotype.Service;
import ru.morkamo.enterprisetester.dto.request.LoginRequest;
import ru.morkamo.enterprisetester.dto.response.LoginResponse;
import ru.morkamo.enterprisetester.model.User;
import ru.morkamo.enterprisetester.repository.UserRepository;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<User> getUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public LoginResponse login(LoginRequest request) {

        var authSuccessMessage = "Успешная авторизация";
        var authErrorMessage = "Неверный логин или пароль";

        if (request.email().isBlank() ||
                request.password().isBlank())
        {
            return new LoginResponse(
                    false,
                    authErrorMessage
            );
        }

        var user = userRepository.findByEmail(request.email());
        if (user.isEmpty()) {
            return new LoginResponse(
                    false,
                    authErrorMessage
            );
        }

        if (!user.get().getPassword()
                .equals(request.password()))
        {
            return new LoginResponse(
                    false,
                    authErrorMessage
            );
        }

        return new LoginResponse(
                true,
                authSuccessMessage,
                user.get().getFirstName() + " " + user.get().getLastName(),
                user.get().getId()
        );
    }
}
