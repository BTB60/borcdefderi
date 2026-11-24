package az.borcdefteri.reports;

import az.borcdefteri.common.ApiException;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * A QR code as SVG (one path, so it stays sharp at any print size) for bonus cards. It only encodes the text it is
 * given — a card number or a link — no store data involved.
 */
@RestController
public class QrImageController {

    @GetMapping("/api/public/qr.svg")
    public ResponseEntity<String> qr(@RequestParam String text, @RequestParam(defaultValue = "#000000") String color) {
        String v = text.trim();
        if (v.isEmpty() || v.length() > 300) throw ApiException.badRequest("INVALID_QR", "QR mətni düzgün deyil");
        String fill = color.matches("#[0-9a-fA-F]{6}") ? color : "#000000";
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(v, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1,
                            EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name()));
        } catch (WriterException e) {
            throw ApiException.badRequest("INVALID_QR", "QR yaradılmadı");
        }
        int w = m.getWidth(), h = m.getHeight();
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m.get(x, y)) continue;
                int run = 1;
                while (x + run < w && m.get(x + run, y)) run++;
                path.append('M').append(x).append(' ').append(y).append('h').append(run).append("v1h-").append(run).append('z');
                x += run - 1;
            }
        }
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " " + h + "\" shape-rendering=\"crispEdges\">"
                + "<rect width=\"" + w + "\" height=\"" + h + "\" fill=\"#ffffff\"/><path fill=\"" + fill + "\" d=\"" + path + "\"/></svg>";
        return ResponseEntity.ok().contentType(MediaType.valueOf("image/svg+xml"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(svg);
    }
}
