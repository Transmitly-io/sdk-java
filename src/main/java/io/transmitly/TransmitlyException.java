package io.transmitly;

import com.fasterxml.jackson.databind.JsonNode;

/** Raised for a non-2xx response or a transport failure. */
public final class TransmitlyException extends RuntimeException {
    private final int status;
    private final String code;
    private final JsonNode details;
    private final String payload;

    public TransmitlyException(int status, String code, String message, JsonNode details, String payload) {
        super(message == null || message.isBlank() ? "Request failed (" + status + ")" : message);
        this.status = status;
        this.code = code == null ? "" : code;
        this.details = details;
        this.payload = payload;
    }

    public int status() { return status; }
    public String code() { return code; }
    public JsonNode details() { return details; }
    public String payload() { return payload; }
}
