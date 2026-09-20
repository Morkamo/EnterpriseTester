package ru.morkamo.enterprisetester.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "testing")
@Getter @Setter
public class TestSettings {
    private boolean enableTimeLimit = true;
    private boolean showCountdown = true;
    private int defaultQuestionsCount = 20;
    private MultiAnswerMode multiAnswerMode = MultiAnswerMode.PARTIAL;
}
