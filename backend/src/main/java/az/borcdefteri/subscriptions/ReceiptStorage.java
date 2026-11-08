package az.borcdefteri.subscriptions;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/**
 * Receipt validation: whitelisted types checked by magic bytes (not the client-supplied header) and a size limit.
 * The bytes are stored in PostgreSQL (subscription_receipts.content), because hosting disks such as Render's are
 * wiped on every deploy. Receipts uploaded before that change are still read from UPLOAD_DIR (legacy).
 * Files are never served statically; only through authorised API endpoints.
 */
@Component
public class ReceiptStorage {

    public record Stored(String storageName, String contentType, long sizeBytes, String originalName, byte[] content) {}

    private final Path baseDir;
    private final long maxBytes;

    public ReceiptStorage(AppProperties props) {
        this.baseDir = Path.of(props.upload().dir()).toAbsolutePath().normalize();
        this.maxBytes = props.upload().maxBytes();
    }

    public Stored store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("RECEIPT_REQUIRED", "Ödəniş qəbzini yükləyin");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Faylın ölçüsü 5 MB-dan çox ola bilməz");
        }
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readNBytes((int) maxBytes + 1);
        } catch (IOException e) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "Fayl oxuna bilmədi");
        }
        if (bytes.length > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Faylın ölçüsü 5 MB-dan çox ola bilməz");
        }
        String type = detectType(bytes);
        if (type == null) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "Yalnız PNG, JPG və ya PDF formatında qəbz qəbul olunur");
        }
        String ext = switch (type) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> ".pdf";
        };
        return new Stored("db-" + UUID.randomUUID() + ext, type, bytes.length,
                sanitizeOriginal(file.getOriginalFilename()), bytes);
    }

    /** Receipts saved on disk before they were moved into the database. */
    public byte[] readLegacy(String storageName) {
        Path p = baseDir.resolve(storageName).normalize();
        if (!p.startsWith(baseDir) || !Files.isRegularFile(p)) {
            throw ApiException.notFound("Qəbz faylı tapılmadı");
        }
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw ApiException.notFound("Qəbz faylı tapılmadı");
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
