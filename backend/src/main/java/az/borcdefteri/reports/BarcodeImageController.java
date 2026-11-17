package az.borcdefteri.reports;

import az.borcdefteri.common.ApiException;
import com.lowagie.text.pdf.Barcode;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.BarcodeEAN;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;

/**
 * A barcode picture (PNG, bars only) for labels printed straight to a label printer by the Windows till.
 * It only draws the text it is given (EAN-13 for 13 digits, otherwise Code 128) — no store data involved.
 */
@RestController
public class BarcodeImageController {

    @GetMapping("/api/public/barcode.png")
    public ResponseEntity<byte[]> barcode(@RequestParam String text) {
        String v = text.trim();
        if (!v.matches("[0-9A-Za-z\\-]{1,48}")) throw ApiException.badRequest("INVALID_BARCODE", "Barkod düzgün deyil");
        Barcode code;
        if (v.matches("\\d{13}")) {
            code = new BarcodeEAN();
            code.setCodeType(Barcode.EAN13);
        } else {
            code = new Barcode128();
            code.setCodeType(Barcode.CODE128);
        }
        code.setCode(v);
        Image awt = code.createAwtImage(Color.BLACK, Color.WHITE);
        int scale = 4, w = awt.getWidth(null), h = 60;
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(awt, 0, 0, w * scale, h * scale, null);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic()).body(out.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
