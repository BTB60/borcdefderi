package az.borcdefteri.auth;

import az.borcdefteri.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends the "Şifrəni unutdum" e-mail. Enabled only when MAIL_HOST is configured; otherwise users recover through
 * the platform admin (temporary password). Sending failures are logged, never shown to the caller (the response
 * must not reveal whether an account exists).
 */
@Component
public class PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String username;
    private final AppProperties props;

    public PasswordResetMailer(ObjectProvider<JavaMailSender> sender,
                               @Value("${spring.mail.host:}") String host,
                               @Value("${spring.mail.username:}") String username,
                               AppProperties props) {
        this.sender = sender;
        this.host = host;
        this.username = username;
        this.props = props;
    }

    public boolean enabled() {
        return host != null && !host.isBlank() && sender.getIfAvailable() != null;
    }

    /** Returns true if the message was handed to the SMTP server. */
    public boolean send(String to, String fullName, String link, int minutes) {
        JavaMailSender mail = sender.getIfAvailable();
        if (!enabled() || mail == null) {
            return false;
        }
        String from = props.passwordReset().mailFrom();
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from != null && !from.isBlank() ? from : username);
        msg.setTo(to);
        msg.setSubject("BORCDEFTERI — şifrənin bərpası");
        msg.setText("""
                Salam, %s!

                BORCDEFTERI hesabınız üçün şifrənin bərpası istənilib. Yeni şifrə təyin etmək üçün linkə keçin:

                %s

                Link %d dəqiqə etibarlıdır və yalnız bir dəfə istifadə oluna bilər.
                Bu sorğunu siz göndərməmisinizsə, bu məktubu nəzərə almayın — şifrəniz dəyişməyəcək.
                """.formatted(fullName, link, minutes));
        try {
            mail.send(msg);
            return true;
        } catch (RuntimeException e) {
            log.warn("Password reset e-mail could not be sent: {}", e.getMessage());
            return false;
        }
    }
}
