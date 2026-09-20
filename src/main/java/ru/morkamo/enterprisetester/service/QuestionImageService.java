package ru.morkamo.enterprisetester.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class QuestionImageService {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;
    private final Path directory;

    public QuestionImageService(@Value("${testing.upload-dir:assets/questions}") String uploadDir) {
        directory = applicationDirectory().resolve(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            throw new IllegalStateException("Не удалось открыть каталог изображений: " + directory, error);
        }
        org.slf4j.LoggerFactory.getLogger(QuestionImageService.class)
                .info("Каталог изображений: {}", directory);
    }

    private static Path applicationDirectory() {
        String home = System.getProperty("enterprisetester.home");
        if (home != null && !home.isBlank()) return Path.of(home);
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null && !launcher.isBlank()) return Path.of(launcher).toAbsolutePath().getParent();
        var source = new org.springframework.boot.system.ApplicationHome(
                ru.morkamo.enterprisetester.EnterpriseTesterApplication.class).getSource();
        if (source != null && source.isFile()) return source.toPath().toAbsolutePath().getParent();
        return Path.of(System.getProperty("user.dir"));
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        String original = file.getOriginalFilename();
        String extension = original == null ? "" : original.toLowerCase(Locale.ROOT);
        String contentType = file.getContentType();
        boolean png = extension.endsWith(".png") && "image/png".equals(contentType);
        boolean jpeg = (extension.endsWith(".jpg") || extension.endsWith(".jpeg"))
                && "image/jpeg".equals(contentType);
        if (!png && !jpeg) throw invalid("Загрузите PNG или JPEG");
        if (file.getSize() > MAX_BYTES) throw invalid("Каждое изображение должно быть не больше 10 МБ");

        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_BYTES) throw invalid("Каждое изображение должно быть не больше 10 МБ");
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid("Файл не является изображением");
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (png && !"png".equals(format) || jpeg && !"jpeg".equals(format)) {
                        throw invalid("Формат файла не соответствует расширению");
                    }
                    long width = reader.getWidth(0);
                    long height = reader.getHeight(0);
                    if (width < 1 || height < 1) throw invalid("Изображение повреждено");
                    int subsampling = (int) Math.ceil(Math.sqrt((double) width * height / MAX_PIXELS));
                    var parameters = reader.getDefaultReadParam();
                    if (subsampling > 1) parameters.setSourceSubsampling(subsampling, subsampling, 0, 0);
                    var image = reader.read(0, parameters);
                    if (image == null) throw invalid("Не удалось прочитать изображение");
                    var output = new ByteArrayOutputStream();
                    if (!ImageIO.write(image, png ? "png" : "jpeg", output)) {
                        throw invalid("Не удалось обработать изображение");
                    }
                    if (output.size() > MAX_BYTES) throw invalid("Изображение после обработки больше 10 МБ");
                    String name = UUID.randomUUID() + (png ? ".png" : ".jpg");
                    Files.createDirectories(directory);
                    Files.write(directory.resolve(name), output.toByteArray(), StandardOpenOption.CREATE_NEW);
                    return name;
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException error) {
            throw invalid("Не удалось сохранить изображение");
        }
    }

    public List<String> storeAll(List<MultipartFile> files) {
        if (files == null) return List.of();
        var uploads = files.stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (uploads.size() > 10) throw invalid("К вопросу можно прикрепить не больше 10 изображений");
        var names = new ArrayList<String>();
        try {
            for (var file : uploads) names.add(store(file));
            return names;
        } catch (RuntimeException error) {
            for (var name : names) {
                try { Files.deleteIfExists(directory.resolve(name)); } catch (IOException ignored) { }
            }
            throw error;
        }
    }

    public byte[] read(String name) {
        if (name == null || !name.matches("[0-9a-fA-F-]{36}\\.(png|jpg)")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            return Files.readAllBytes(directory.resolve(name));
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Изображение не найдено");
        }
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
