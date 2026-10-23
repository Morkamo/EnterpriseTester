package ru.morkamo.enterprisetester.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.morkamo.enterprisetester.model.Answer;
import ru.morkamo.enterprisetester.model.Question;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class QuestionImportService {
    public List<Question> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw error(0, "Выберите CSV-файл");
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw error(0, "Можно импортировать только CSV-файлы");
        }
        if (file.getSize() > 10 * 1024 * 1024) throw error(0, "Размер CSV-файла не должен превышать 10 МБ");
        try {
            String text = decode(file.getBytes());
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            var format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter(text))
                    .setIgnoreEmptyLines(false).setIgnoreSurroundingSpaces(true).setTrim(true).get();
            try (var parser = format.parse(new java.io.StringReader(text))) {
                var result = new ArrayList<Question>();
                Question current = null;
                long questionRow = 0;
                for (var row : parser) {
                    long number = row.getRecordNumber();
                    if (row.stream().allMatch(String::isBlank)) continue;
                    if (row.size() < 4 || row.size() > 5) throw error(number, "Нужно 5 колонок: заголовок, содержание, ответ, баллы, множественный выбор");
                    if (!row.get(0).isBlank()) {
                        if (current != null) validate(current, questionRow);
                        if (result.size() == 1000) throw error(number, "За один раз можно импортировать не больше 1000 вопросов");
                        if (row.get(0).length() > 255) throw error(number, "Заголовок должен быть не длиннее 255 символов");
                        if (row.get(1).isBlank()) throw error(number, "Введите содержание вопроса в колонке B");
                        current = new Question();
                        current.setTitle(row.get(0));
                        current.setText(row.get(1));
                        String multiple = row.size() == 5 ? row.get(4) : "";
                        if (!multiple.isEmpty() && !multiple.equals("0") && !multiple.equals("1")) {
                            throw error(number, "В колонке E допустимы только 1, 0 или пустое значение");
                        }
                        current.setMultipleAllowed(multiple.equals("1"));
                        result.add(current);
                        questionRow = number;
                    } else {
                        if (current == null) throw error(number, "Укажите заголовок и содержание первого вопроса");
                        if (!row.get(1).isBlank() || (row.size() == 5 && !row.get(4).isBlank())) {
                            throw error(number, "Содержание и множественный выбор указываются только в первой строке вопроса");
                        }
                    }
                    if (row.get(2).isBlank()) throw error(number, "Введите вариант ответа в колонке C");
                    var answer = new Answer();
                    answer.setText(row.get(2));
                    answer.setPoints(points(row.get(3), number));
                    answer.setCorrect(answer.getPoints().signum() > 0);
                    answer.setQuestion(current);
                    current.getAnswers().add(answer);
                }
                if (current == null) throw error(0, "В файле нет вопросов");
                validate(current, questionRow);
                return result;
            }
        } catch (IOException | UncheckedIOException error) {
            throw error(0, "Некорректный CSV: проверьте разделители и парные кавычки");
        }
    }

    private char delimiter(String text) throws IOException {
        for (char delimiter : new char[]{';', ','}) {
            var format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get();
            try (var parser = format.parse(new java.io.StringReader(text))) {
                for (CSVRecord row : parser) {
                    if (row.stream().allMatch(String::isBlank)) continue;
                    if (row.size() >= 4 && row.size() <= 5) return delimiter;
                    break;
                }
            } catch (UncheckedIOException ignored) {}
        }
        throw error(0, "Некорректный CSV: ожидаются колонки A–E с разделителем «;» или «,»");
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            return Charset.forName("windows-1251").decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private BigDecimal points(String text, long row) {
        if (text.isEmpty()) return BigDecimal.ZERO;
        if (!text.matches("(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)")) {
            throw error(row, "Баллы должны быть неотрицательным числом");
        }
        var value = new BigDecimal(text.replace(',', '.'));
        if (value.compareTo(new BigDecimal("9999999999999999.99")) > 0) throw error(row, "Слишком большое количество баллов");
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private void validate(Question question, long row) {
        if (question.getAnswers().size() < 2 || question.getAnswers().stream().noneMatch(Answer::isCorrect)) {
            throw error(row, "Нужно минимум два ответа и хотя бы один ответ с баллами");
        }
    }

    private ResponseStatusException error(long row, String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, (row == 0 ? "" : "Строка " + row + ": ") + message);
    }
}
