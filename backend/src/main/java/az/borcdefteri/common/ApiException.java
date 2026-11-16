package az.borcdefteri.common;

import org.springframework.http.HttpStatus;

/** Business error with a stable machine-readable code and an (Azerbaijani) user message. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final java.util.Map<String, String> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    /** With extra values for the client (sent as "fields"), e.g. the id of a login challenge. */
    public ApiException(HttpStatus status, String code, String message, java.util.Map<String, String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public java.util.Map<String, String> getDetails() {
        return details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
