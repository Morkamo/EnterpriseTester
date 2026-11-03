package ru.morkamo.enterprisetester.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.morkamo.enterprisetester.model.Answer;
import ru.morkamo.enterprisetester.model.Question;
import ru.morkamo.enterprisetester.model.TestDefinition;
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
        return parseQuestions(readRows(file, false));
    }

    public TestDefinition parseTest(MultipartFile file) {
        var rows = readRows(file, true);
        if (rows.isEmpty()) throw error(0, "В файле нет данных теста");
        var header = rows.get(0);
        if (header.size() < 3 || header.size() > 4 || header.get(0).isBlank()) {
            throw error(header.getRecordNumber(), "Укажите название, описание и время теста в первой строке");
        }
        if (header.size() == 4 && !header.get(3).isBlank()) {
            throw error(header.getRecordNumber(), "Четвёртая колонка первой строки должна быть пустой");
        }
        if (header.get(0).length() > 255) throw error(1, "Название теста должно быть не длиннее 255 символов");
        var test = new TestDefinition();
        test.setName(header.get(0));
        test.setDescription(header.get(1));
        if (!header.get(2).isBlank()) {
            try {
                if (!header.get(2).matches("[0-9]+")) throw new NumberFormatException();
                int minutes = Integer.parseInt(header.get(2));
                if (minutes < 1 || minutes > 1_000_000) throw new NumberFormatException();
                test.setTemporaryTest(true);
                test.setTimeMinutes(minutes);
            } catch (NumberFormatException error) {
                throw error(header.getRecordNumber(), "Время должно быть от 1 до 1000000 минут или пустым");
            }
        }
        test.setQuestions(parseQuestions(rows.subList(1, rows.size())));
        return test;
    }

    private List<CSVRecord> readRows(MultipartFile file, boolean test) {
        if (file == null || file.isEmpty()) throw error(0, "Выберите CSV-файл");
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw error(0, "Можно импортировать только CSV-файлы");
        }
        if (file.getSize() > 10 * 1024 * 1024) throw error(0, "Размер CSV-файла не должен превышать 10 МБ");
        try {
            String text = decode(file.getBytes());
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            var format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter(text, test))
                    .setIgnoreEmptyLines(false).setIgnoreSurroundingSpaces(true).setTrim(true).get();
            try (var parser = format.parse(new java.io.StringReader(text))) {
                return parser.getRecords().stream()
                        .filter(row -> !row.stream().allMatch(String::isBlank)).toList();
            }
        } catch (IOException | UncheckedIOException error) {
            throw error(0, "Некорректный CSV: проверьте разделители и парные кавычки");
        }
    }

    private List<Question> parseQuestions(List<CSVRecord> rows) {
        var result = new ArrayList<Question>();
        Question current = null;
        long questionRow = 0;
        for (var row : rows) {
            long number = row.getRecordNumber();
            if (row.stream().allMatch(String::isBlank)) continue;
            if (row.size() < 3 || row.size() > 4) {
                throw error(number, "Нужны колонки: текст вопроса, ответ, баллы, множественный выбор");
            }
            if (!row.get(0).isBlank()) {
                if (current != null) validate(current, questionRow);
                if (result.size() == 1000) throw error(number, "За один раз можно импортировать не больше 1000 вопросов");
                current = new Question();
                current.setText(row.get(0));
                String multiple = "";
                if (row.size() == 4) multiple = row.get(3);
                if (!multiple.isEmpty() && !multiple.equals("0") && !multiple.equals("1")) {
                    throw error(number, "В колонке D допустимы только 1, 0 или пустое значение");
                }
                current.setMultipleAllowed(multiple.equals("1"));
                result.add(current);
                questionRow = number;
            } else {
                if (current == null) throw error(number, "Укажите текст первого вопроса в колонке A");
                if (row.size() == 4 && !row.get(3).isBlank()) {
                    throw error(number, "Множественный выбор указывается только в первой строке вопроса");
                }
            }
            if (row.get(1).isBlank()) throw error(number, "Введите вариант ответа в колонке B");
            var answer = new Answer();
            answer.setText(row.get(1));
            answer.setPoints(points(row.get(2), number));
            answer.setCorrect(answer.getPoints().signum() > 0);
            answer.setQuestion(current);
            current.getAnswers().add(answer);
        }
        if (current == null) throw error(0, "В файле нет вопросов");
        validate(current, questionRow);
        return result;
    }

    private char delimiter(String text, boolean test) throws IOException {
        for (char delimiter : new char[]{';', ','}) {
            var format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get();
            try (var parser = format.parse(new java.io.StringReader(text))) {
                for (CSVRecord row : parser) {
                    if (row.stream().allMatch(String::isBlank)) continue;
                    if (row.size() == 4 || (row.size() == 3 && test)) return delimiter;
                    if (row.size() == 3 && !test) {
                        String value = row.get(2);
                        if (value.isBlank() || value.matches("(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)")) return delimiter;
                    }
                    break;
                }
            } catch (UncheckedIOException ignored) {}
        }
        throw error(0, "Некорректный CSV: ожидаются колонки A–D с разделителем «;» или «,»");
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
