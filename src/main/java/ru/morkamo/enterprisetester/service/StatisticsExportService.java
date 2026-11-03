package ru.morkamo.enterprisetester.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import ru.morkamo.enterprisetester.model.TestAttempt;
import ru.morkamo.enterprisetester.model.TestDefinition;
import ru.morkamo.enterprisetester.model.User;
import ru.morkamo.enterprisetester.model.AttemptQuestion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class StatisticsExportService {
    private static final String[] HEADERS = {
            "Пользователь", "Название теста", "Верных ответов", "Всего вопросов",
            "Процент выполнения", "Дата начала", "Дата окончания", "Длительность"
    };
    private static final String[] ANSWER_HEADERS = {
            "Пользователь", "№ вопроса", "Вопрос", "Ответ пользователя", "Правильные ответы"
    };
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    public byte[] csv(TestDefinition test, List<TestAttempt> attempts, Map<Long, User> users) {
        var csv = new StringBuilder("\uFEFF");
        csv.append(String.join(";", HEADERS)).append("\r\n");
        for (var attempt : attempts) {
            csv.append(String.join(";",
                    csvValue(userName(attempt.getUserId(), users)),
                    csvValue(test.getName()),
                    String.valueOf(attempt.getCorrectCount()),
                    String.valueOf(attempt.getQuestions().size()),
                    String.valueOf(attempt.getPercentage()),
                    csvValue(DATE_FORMAT.format(attempt.getStartedAt())),
                    csvValue(attempt.getFinishedAt() == null ? "" : DATE_FORMAT.format(attempt.getFinishedAt())),
                    csvValue(durationText(attempt))));
            csv.append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] answersCsv(List<TestAttempt> attempts, Map<Long, User> users) {
        var csv = new StringBuilder("\uFEFF");
        csv.append(String.join(";", ANSWER_HEADERS)).append("\r\n");
        for (var attempt : attempts) {
            for (int index = 0; index < attempt.getQuestions().size(); index++) {
                var values = answerValues(attempt, index, users);
                for (int column = 0; column < values.length; column++) {
                    if (column > 0) csv.append(';');
                    csv.append(csvValue(values[column]));
                }
                csv.append("\r\n");
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] xlsx(TestDefinition test, List<TestAttempt> attempts, Map<Long, User> users, boolean extended) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Результаты");
            sheet.createFreezePane(0, 1);

            var headerStyle = headerStyle(workbook);
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd.mm.yyyy hh:mm:ss"));
            var percentStyle = workbook.createCellStyle();
            percentStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
            var durationStyle = workbook.createCellStyle();
            durationStyle.setDataFormat(workbook.createDataFormat().getFormat("[h]:mm:ss"));

            var header = sheet.createRow(0);
            for (int column = 0; column < HEADERS.length; column++) {
                var cell = header.createCell(column);
                cell.setCellValue(HEADERS[column]);
                cell.setCellStyle(headerStyle);
            }

            for (int index = 0; index < attempts.size(); index++) {
                writeRow(sheet.createRow(index + 1), test, attempts.get(index), users,
                        dateStyle, percentStyle, durationStyle);
            }

            int[] widths = {28, 28, 18, 18, 20, 22, 22, 16};
            for (int column = 0; column < widths.length; column++) {
                sheet.setColumnWidth(column, widths[column] * 256);
            }
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, attempts.size()), 0, HEADERS.length - 1));
            if (extended) writeAnswers(workbook, attempts, users, headerStyle);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Не удалось сформировать Excel-файл", error);
        }
    }

    private void writeAnswers(Workbook workbook, List<TestAttempt> attempts, Map<Long, User> users,
                              CellStyle headerStyle) {
        var sheet = workbook.createSheet("Ответы");
        sheet.createFreezePane(2, 1);
        var header = sheet.createRow(0);
        for (int column = 0; column < ANSWER_HEADERS.length; column++) {
            var cell = header.createCell(column);
            cell.setCellValue(ANSWER_HEADERS[column]);
            cell.setCellStyle(headerStyle);
        }
        var textStyle = workbook.createCellStyle();
        textStyle.setWrapText(true);
        textStyle.setVerticalAlignment(VerticalAlignment.TOP);
        int rowIndex = 1;
        for (var attempt : attempts) {
            for (int index = 0; index < attempt.getQuestions().size(); index++) {
                var row = sheet.createRow(rowIndex++);
                var values = answerValues(attempt, index, users);
                int lineCount = 1;
                for (int column = 2; column < values.length; column++) {
                    int lines = 0;
                    for (String line : values[column].split("\n", -1)) {
                        lines += Math.max(1, (line.length() + 59) / 60);
                    }
                    lineCount = Math.max(lineCount, lines);
                }
                row.setHeightInPoints(Math.min(409, lineCount * 15));
                for (int column = 0; column < values.length; column++) {
                    var cell = row.createCell(column);
                    if (column == 1) {
                        cell.setCellValue(index + 1);
                    } else {
                        cell.setCellValue(values[column]);
                    }
                    cell.setCellStyle(textStyle);
                }
            }
        }
        int[] widths = {28, 14, 65, 65, 65};
        for (int column = 0; column < widths.length; column++) {
            sheet.setColumnWidth(column, widths[column] * 256);
        }
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, ANSWER_HEADERS.length - 1));
    }

    private String[] answerValues(TestAttempt attempt, int index, Map<Long, User> users) {
        AttemptQuestion question = attempt.getQuestions().get(index);
        var selected = new java.util.ArrayList<String>();
        var correct = new java.util.ArrayList<String>();
        for (var option : question.getOptions()) {
            if (option.isSelected()) selected.add(option.getText());
            if (option.isCorrect()) correct.add(option.getText());
        }
        String selectedText = "Нет ответа";
        if (!selected.isEmpty()) selectedText = String.join("\n", selected);
        return new String[]{userName(attempt.getUserId(), users), String.valueOf(index + 1),
                question.getText(), selectedText, String.join("\n", correct)};
    }

    private void writeRow(Row row, TestDefinition test, TestAttempt attempt, Map<Long, User> users,
                          CellStyle dateStyle, CellStyle percentStyle, CellStyle durationStyle) {
        row.createCell(0).setCellValue(userName(attempt.getUserId(), users));
        row.createCell(1).setCellValue(test.getName());
        row.createCell(2).setCellValue(attempt.getCorrectCount());
        row.createCell(3).setCellValue(attempt.getQuestions().size());

        var percent = row.createCell(4);
        percent.setCellValue(attempt.getPercentage() / 100);
        percent.setCellStyle(percentStyle);

        var started = row.createCell(5);
        started.setCellValue(java.util.Date.from(attempt.getStartedAt()));
        started.setCellStyle(dateStyle);

        var finished = row.createCell(6);
        if (attempt.getFinishedAt() != null) {
            finished.setCellValue(java.util.Date.from(attempt.getFinishedAt()));
        }
        finished.setCellStyle(dateStyle);

        var duration = row.createCell(7);
        duration.setCellValue(duration(attempt).toSeconds() / 86400.0);
        duration.setCellStyle(durationStyle);
    }

    private CellStyle headerStyle(Workbook workbook) {
        var style = workbook.createCellStyle();
        var font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private String userName(Long userId, Map<Long, User> users) {
        var user = users.get(userId);
        if (user == null) return "Удалённый пользователь #" + userId;
        var fullName = (user.getLastName() + " " + user.getFirstName()).trim();
        return fullName.isBlank() ? user.getEmail() : fullName;
    }

    private String csvValue(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private Duration duration(TestAttempt attempt) {
        return Duration.between(attempt.getStartedAt(),
                attempt.getFinishedAt() == null ? java.time.Instant.now() : attempt.getFinishedAt());
    }

    private String durationText(TestAttempt attempt) {
        long seconds = duration(attempt).toSeconds();
        return String.format("%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }
}
