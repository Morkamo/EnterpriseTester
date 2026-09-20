package ru.morkamo.enterprisetester.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tests")
@Getter @Setter
public class TestDefinition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long ownerId;
    private String name;
    @Column(columnDefinition = "TEXT")
    private String description;
    @Column(name = "is_temporary_test")
    private boolean temporaryTest;
    @Column(name = "is_test_closed")
    private boolean testClosed;
    private Integer timeMinutes;
    @ManyToMany
    @JoinTable(name = "test_testers",
            joinColumns = @JoinColumn(name = "test_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private List<User> testers = new ArrayList<>();
    @ManyToMany
    @JoinTable(name = "test_questions",
            joinColumns = @JoinColumn(name = "test_id"),
            inverseJoinColumns = @JoinColumn(name = "question_id"))
    private List<Question> questions = new ArrayList<>();
}
