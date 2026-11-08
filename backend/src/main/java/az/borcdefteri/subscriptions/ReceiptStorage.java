package az.borcdefteri.subscriptions;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.AppProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/**
 * Secure receipt storage: whitelisted types checked by magic bytes (not the client-supplied header),
 * size limit, random server-side file names, and path-traversal protection. Files are never served statically;
 * they are only streamed through an authorised API endpoint.
 */
@Component
public class ReceiptStorage {

    public record Stored(String storageName, String contentType, long sizeBytes, String originalName) {}

    private final Path baseDir;
    private final long maxBytes;

    public ReceiptStorage(AppProperties props) {
        this.baseDir = Path.of(props.upload().dir()).toAbsolutePath().normalize();
        this.maxBytes = props.upload().maxBytes();
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(baseDir);
    }

    public Stored store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("RECEIPT_REQUIRED", "Ödəniş qəbzini yükləyin");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "Faylın ölçüsü 5 MB-dan çox ola bilməz");
        }
        byte[] head = readHead(file);
        String type = detectType(head);
        if (type == null) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "Yalnız PNG, JPG və ya PDF formatında qəbz qəbul olunur");
        }
        String ext = switch (type) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> ".pdf";
        };
        String storageName = UUID.randomUUID() + ext;
        Path target = baseDir.resolve(storageName).normalize();
        if (!target.startsWith(baseDir)) {
            throw ApiException.badRequest("INVALID_FILE_NAME", "Fayl adı qəbul edilmir");
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target);
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_FAILED",
                    "Qəbz saxlanıla bilmədi");
        }
        return new Stored(storageName, type, file.getSize(), sanitizeOriginal(file.getOriginalFilename()));
    }

    public Path resolve(String storageName) {
        Path p = baseDir.resolve(storageName).normalize();
        if (!p.startsWith(baseDir) || !Files.exists(p)) {
            throw ApiException.notFound("Qəbz tapılmadı");
        }
        return p;
    }

    public void delete(String storageName) {
        try {
            Files.deleteIfExists(baseDir.resolve(storageName).normalize());
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(8);
        } catch (IOException e) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "Fayl oxuna bilmədi");
        }
    }

    static String detectType(byte[] h) {
        if (h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return "image/png";
        }
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (h.length >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') {
            return "application/pdf";
        }
        return null;
    }

    private static String sanitizeOriginal(String name) {
        if (name == null) {
            return null;
        }
        String base = Arrays.stream(name.split("[\\\\/]")).reduce((a, b) -> b).orElse("qəbz");
        String clean = base.replaceAll("[^\\p{L}\\p{N} ._-]", "_");
        return clean.length() > 200 ? clean.substring(clean.length() - 200) : clean;
    }
}
