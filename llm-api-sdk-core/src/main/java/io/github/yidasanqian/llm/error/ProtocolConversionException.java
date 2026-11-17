package io.github.yidasanqian.llm.error;

/** A request validation or response conversion failure, without a web framework dependency. */
public final class ProtocolConversionException extends IllegalArgumentException {
    private final int statusCode;

    private ProtocolConversionException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }

    public static ProtocolConversionException badRequest(String message) {
        return new ProtocolConversionException(message, 400);
    }

    public static ProtocolConversionException of(String message, int statusCode) {
        return new ProtocolConversionException(message, statusCode);
    }
}
