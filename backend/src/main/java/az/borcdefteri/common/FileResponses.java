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
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }
}
