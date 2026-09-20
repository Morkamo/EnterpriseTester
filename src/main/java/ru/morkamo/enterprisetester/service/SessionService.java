package ru.morkamo.enterprisetester.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionService {
    public static final long USER = 1;
    public static final long ADMIN = 2;
    public static final long MANAGER = 3;

    private final ru.morkamo.enterprisetester.repository.UserRepository users;

    public SessionService(ru.morkamo.enterprisetester.repository.UserRepository users) {
        this.users = users;
    }

    public Long userId(HttpSession session) {
        var id = (Long) session.getAttribute("userId");
        var user = id == null ? java.util.Optional.<ru.morkamo.enterprisetester.model.User>empty()
                : users.findById(id).filter(account -> !account.isDeleted());
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (user.isEmpty() || (auth != null && auth.isAuthenticated()
                && !auth.getName().equals(user.get().getEmail()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return id;
    }

    public boolean isActive(HttpSession session) {
        try {
            userId(session);
            return true;
        } catch (ResponseStatusException error) {
            return false;
        }
    }

    public long roleId(HttpSession session) {
        var id = (Long) session.getAttribute("userId");
        return id == null ? USER : users.findById(id)
                .filter(user -> !user.isDeleted())
                .map(user -> user.getUserRole() == null ? USER : user.getUserRole())
                .orElse(USER);
    }

    public boolean canManage(HttpSession session) {
        return isActive(session) && (roleId(session) == ADMIN || roleId(session) == MANAGER);
    }

    public boolean isAdmin(HttpSession session) {
        return isActive(session) && roleId(session) == ADMIN;
    }

    public void addHeader(HttpSession session, org.springframework.ui.Model model) {
        model.addAttribute("fullName", session.getAttribute("fullName"));
        model.addAttribute("canManage", canManage(session));
        model.addAttribute("isAdmin", isAdmin(session));
        model.addAttribute("roleName", isAdmin(session) ? "Администратор" :
                (roleId(session) == MANAGER ? "Менеджер" : "Пользователь"));
    }
}
