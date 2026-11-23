package az.borcdefteri.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * The SMS provider, chosen by configuration (app.sms.*), so a provider is connected without a code change:
 * "http" fits most local providers (a URL / body template), "twilio" uses Twilio's REST API. Empty = off.
 */
@Component
public class HttpSmsGateway implements SmsGateway {

    private final String provider;
    private final String sender;
    private final String url;
    private final String method;
    private final String body;
    private final String contentType;
    private final String auth;
    private final String twilioSid;
    private final String twilioToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public HttpSmsGateway(@Value("${app.sms.provider:}") String provider, @Value("${app.sms.sender:HesabPro}") String sender,
                          @Value("${app.sms.http-url:}") String url, @Value("${app.sms.http-method:GET}") String method,
                          @Value("${app.sms.http-body:}") String body,
                          @Value("${app.sms.http-content-type:application/json}") String contentType,
                          @Value("${app.sms.http-auth:}") String auth,
                          @Value("${app.sms.twilio-sid:}") String twilioSid, @Value("${app.sms.twilio-token:}") String twilioToken) {
        this.provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        this.sender = sender == null ? "" : sender.trim();
        this.url = url == null ? "" : url.trim();
        this.method = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        this.body = body == null ? "" : body;
        this.contentType = contentType == null || contentType.isBlank() ? "application/json" : contentType.trim();
        this.auth = auth == null ? "" : auth.trim();
        this.twilioSid = twilioSid == null ? "" : twilioSid.trim();
        this.twilioToken = twilioToken == null ? "" : twilioToken.trim();
    }

    @Override
    public boolean configured() {
        return switch (provider) {
            case "http" -> !url.isEmpty();
            case "twilio" -> !twilioSid.isEmpty() && !twilioToken.isEmpty() && !sender.isEmpty();
            default -> false;
        };
    }

    @Override
    public void send(String phone, String text) throws Exception {
        if (!configured()) throw new IllegalStateException("SMS provider is not configured");
        HttpRequest request = "twilio".equals(provider) ? twilio(phone, text) : generic(phone, text);
        HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            String answer = res.body() == null ? "" : res.body();
            throw new IllegalStateException("HTTP " + res.statusCode() + ": " + answer.substring(0, Math.min(answer.length(), 300)));
        }
    }

    private HttpRequest generic(String phone, String text) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(fill(url, phone, text, false, true)))
                .timeout(Duration.ofSeconds(20));
        if (!auth.isEmpty()) b.header("Authorization", auth);
        if ("POST".equals(method)) {
            boolean json = body.trim().startsWith("{");
            b.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(fill(body, phone, text, json, !json)));
        } else {
            b.GET();
        }
        return b.build();
    }

    private HttpRequest twilio(String phone, String text) {
        String form = "To=" + enc("+" + phone) + "&From=" + enc(sender) + "&Body=" + enc(text);
        return HttpRequest.newBuilder(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + twilioSid + "/Messages.json"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((twilioSid + ":" + twilioToken).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
    }

    private String fill(String template, String phone, String text, boolean json, boolean urlEncode) {
        return template.replace("{to}", esc(phone, json, urlEncode)).replace("{sender}", esc(sender, json, urlEncode))
                .replace("{text}", esc(text, json, urlEncode));
    }

    private static String esc(String v, boolean json, boolean urlEncode) {
        if (json) return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return urlEncode ? enc(v) : v;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
