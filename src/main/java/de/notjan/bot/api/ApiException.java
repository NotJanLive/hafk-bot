package de.notjan.bot.api;

import java.util.List;

public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<String> details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(int status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "bad_request", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "not_found", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "forbidden", message);
    }

    public static ApiException validation(List<String> details) {
        return new ApiException(422, "validation_failed", "Die Eingaben sind ungültig.", details);
    }

    public int status() {
        return status;
    }

    public ErrorBody body() {
        return new ErrorBody(new ErrorBody.Error(code, getMessage(), details));
    }

    public record ErrorBody(Error error) {
        public record Error(String code, String message, List<String> details) {
        }
    }
}
