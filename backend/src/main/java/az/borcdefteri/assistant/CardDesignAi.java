package az.borcdefteri.assistant;

import az.borcdefteri.common.ApiException;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Card Studio's AI designer: one Messages API call with a structured output returns a whole new bonus card look —
 * colours, background style, layout, font, an icon and an original cartoon character drawn as SVG. Uses the same
 * key and model as the assistant (ANTHROPIC_API_KEY); without a key the studio uses its built-in generator.
 */
@Component
public class CardDesignAi {

    private static final Logger log = LoggerFactory.getLogger(CardDesignAi.class);

    /** What the model returns; the server checks every field before it reaches the browser. */
    public record AiCard(
            @JsonPropertyDescription("Short Azerbaijani name for this design, 2-4 words, no brand names") String name,
            @JsonPropertyDescription("Background style, one of: gradient, radial, stripes, dots, waves, confetti, mesh, sunburst, grid") String background,
            @JsonPropertyDescription("Main background colour, #RRGGBB") String color1,
            @JsonPropertyDescription("Second background colour, #RRGGBB, contrasting with color1") String color2,
            @JsonPropertyDescription("Accent colour for badges and the icon, #RRGGBB") String accent,
            @JsonPropertyDescription("Text colour with strong contrast on the background, #RRGGBB") String textColor,
            @JsonPropertyDescription("Font, one of: rounded, serif, bold, mono, playful, elegant") String font,
            @JsonPropertyDescription("Layout, one of: classic, split, centered, badge, diagonal") String layout,
            @JsonPropertyDescription("Small icon, one of: star, gift, crown, heart, cup, cart, sparkle, diamond, smile, leaf") String icon,
            @JsonPropertyDescription("Very short Azerbaijani tagline under the title, max 28 characters, playful, no brand names") String tagline,
            @JsonPropertyDescription("The character as the INNER markup of an SVG with viewBox 0 0 240 240 (no <svg> wrapper)") String characterSvg) {}

    private static final String SYSTEM = """
            You design bonus (loyalty) cards for small shops in Azerbaijan. Each answer is ONE new card look.

            The card already prints the title "BONUS KART", the customer's name, the card number, a QR code, the bonus
            balance and the valid-until date; you only choose the look and draw one character.

            Rules:
            - Never use a company name, brand name, logo or trademark character. Everything must be original.
            - The character is a funny, likeable, ORIGINAL cartoon figure that fits the theme (for example a grinning
              shopping bag with arms, a coffee cup wearing sunglasses, a gift box with a crown, a teddy bear holding a
              star). Make it look 3D: radialGradient / linearGradient shading, a highlight, a soft ground shadow,
              outlines in a darker tone of the fill. Big expressive eyes and a clear smile.
            - characterSvg is the inner markup of <svg viewBox="0 0 240 240">: only <defs>, <linearGradient>,
              <radialGradient>, <stop>, <g>, <path>, <circle>, <ellipse>, <rect>, <polygon>, <polyline>, <line>.
              Gradient ids must start with "c". No text, no <image>, no <use>, no scripts, no event attributes, no
              external links. Keep it under 9000 characters. The figure fills about 80% of the box.
            - Colours are vivid, contrasting and professional; text must stay readable on the background.
            - Do not repeat a previous design by just changing colours: vary the composition, character, palette,
              background and style each time.
            """;

    private static final Set<String> TAGS = Set.of("defs", "lineargradient", "radialgradient", "stop", "g", "path", "circle",
            "ellipse", "rect", "polygon", "polyline", "line");
    private static final Pattern TAG = Pattern.compile("<\\s*/?\\s*([a-zA-Z][a-zA-Z0-9:-]*)");
    private static final Pattern BAD = Pattern.compile("(?i)(\\son[a-z]+\\s*=|href|javascript:|<!|<\\?|url\\(\\s*['\"]?\\s*(https?:|data:)|@import)");

    private final String apiKey;
    private final String model;
    private volatile AnthropicClient client;

    public CardDesignAi(@Value("${app.assistant.api-key:}") String apiKey,
                        @Value("${app.assistant.model:claude-opus-5-5}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            }
        }
        return client;
    }

    public record Result(AiCard card, long inputTokens, long outputTokens) {}

    /** One new design for the theme; {@code idea} is the store's own words (optional), {@code twist} varies each call. */
    public Result design(String category, String idea, String twist) {
        String user = "Theme: " + category + "\n"
                + (idea == null || idea.isBlank() ? "" : "The shop's wish: " + idea.trim() + "\n")
                + "Variation for this one: " + twist + "\nDesign one new bonus card.";
        StructuredMessageCreateParams<AiCard> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(SYSTEM)
                .addUserMessage(user)
                .outputConfig(AiCard.class)
                .build();
        try {
            StructuredMessage<AiCard> msg = client().messages().create(params);
            if (msg.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_REFUSED", "AI bu dizaynı hazırlamadı, başqa mövzu seçin");
            }
            AiCard card = msg.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY, "AI_EMPTY", "AI cavab vermədi, yenidən cəhd edin"));
            return new Result(clean(card), msg.usage().inputTokens(), msg.usage().outputTokens());
        } catch (RateLimitException e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUSY", "AI hazırda məşğuldur, bir az sonra yenidən cəhd edin");
        } catch (AnthropicServiceException e) {
            log.warn("Card design AI error {}: {}", e.statusCode(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR", "AI dizayn hazırlaya bilmədi, bir az sonra yenidən cəhd edin");
        }
    }

    /** Only known values and a safe character drawing reach the page. */
    static AiCard clean(AiCard c) {
        return new AiCard(
                cut(c.name(), 40, "AI dizaynı"),
                pick(c.background(), "gradient", "gradient", "radial", "stripes", "dots", "waves", "confetti", "mesh", "sunburst", "grid"),
                color(c.color1(), "#7C3AED"), color(c.color2(), "#DB2777"), color(c.accent(), "#FACC15"), color(c.textColor(), "#FFFFFF"),
                pick(c.font(), "rounded", "rounded", "serif", "bold", "mono", "playful", "elegant"),
                pick(c.layout(), "classic", "classic", "split", "centered", "badge", "diagonal"),
                pick(c.icon(), "star", "star", "gift", "crown", "heart", "cup", "cart", "sparkle", "diamond", "smile", "leaf"),
                cut(c.tagline(), 28, ""),
                safeSvg(c.characterSvg()));
    }

    /** The character markup, or null when it uses anything outside the drawing allowlist. */
    static String safeSvg(String svg) {
        if (svg == null) return null;
        String s = svg.trim();
        if (s.isEmpty() || s.length() > 20000 || BAD.matcher(s).find()) return null;
        Matcher m = TAG.matcher(s);
        while (m.find()) {
            if (!TAGS.contains(m.group(1).toLowerCase())) return null;
        }
        return s;
    }

    private static String color(String v, String fallback) {
        return v != null && v.trim().matches("#[0-9a-fA-F]{6}") ? v.trim().toUpperCase() : fallback;
    }

    private static String pick(String v, String fallback, String... allowed) {
        if (v == null) return fallback;
        String x = v.trim().toLowerCase();
        for (String a : allowed) if (a.equals(x)) return a;
        return fallback;
    }

    private static String cut(String v, int max, String fallback) {
        if (v == null || v.isBlank()) return fallback;
        String t = v.trim().replaceAll("[<>]", "");
        return t.length() > max ? t.substring(0, max) : t;
    }
}
