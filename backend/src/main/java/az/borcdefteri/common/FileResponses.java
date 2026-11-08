package az.borcdefteri.common;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** Download responses. File names are sent RFC 5987-encoded so Azerbaijani letters (ə, ş, ğ …) survive. */
public final class FileResponses {

    private FileResponses() {}

    public static ResponseEntity<byte[]> attachment(byte[] content, String contentType, String fileName) {
        return file(content, contentType, ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build());
    }

    /** Opens in the browser (PDF viewer) instead of downloading — used for receipts shared via WhatsApp. */
    public static ResponseEntity<byte[]> inline(byte[] content, String contentType, String fileName) {
        return file(content, contentType, ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build());
    }

    private static ResponseEntity<byte[]> file(byte[] content, String contentType, ContentDisposition disposition) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }
}
