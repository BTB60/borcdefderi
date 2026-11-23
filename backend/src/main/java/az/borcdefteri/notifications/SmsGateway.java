package az.borcdefteri.notifications;

/** Sends one SMS through the platform's provider. Throws with the provider's answer when it is refused. */
public interface SmsGateway {

    /** True when a provider is set up (SMS_PROVIDER and its settings). */
    boolean configured();

    /** {@code phone} is in international digits without "+", e.g. 994501234567. */
    void send(String phone, String text) throws Exception;
}
