package io.transmitly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** A file attached to a message. Use base64 content or a public HTTPS path. */
public final class Attachment {
    public String filename;
    public String content;
    public String path;
    public String contentType;
    public String contentId;

    public static Attachment fromFile(Path file) throws IOException {
        return fromFile(file, "application/octet-stream");
    }

    public static Attachment fromFile(Path file, String contentType) throws IOException {
        Attachment attachment = new Attachment();
        attachment.filename = file.getFileName().toString();
        attachment.content = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        attachment.contentType = contentType;
        return attachment;
    }
}
