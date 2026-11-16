package io.transmitly;

/** The accepted message returned by POST /v1/emails. */
public final class EmailAccepted {
    public String id;
    public String status;
    public int recipients;
    public String warning;
    public Boolean testMode;
}
