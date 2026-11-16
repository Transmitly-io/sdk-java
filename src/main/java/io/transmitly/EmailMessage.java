package io.transmitly;

import java.util.List;
import java.util.Map;

/** The message payload for POST /v1/emails. */
public final class EmailMessage {
    public String from;
    public List<String> to;
    public String subject;
    public String html;
    public String text;
    public String replyTo;
    public String stream;
    public Map<String, String> tags;
    public Map<String, String> headers;
    public String templateId;
    public Map<String, String> templateData;
    public Boolean testMode;
    public List<String> cc;
    public List<String> bcc;
    public String scheduledAt;
    public String topic;
    public List<Attachment> attachments;

    public EmailMessage from(String value) { this.from = value; return this; }
    public EmailMessage to(List<String> value) { this.to = value; return this; }
    public EmailMessage subject(String value) { this.subject = value; return this; }
    public EmailMessage html(String value) { this.html = value; return this; }
    public EmailMessage text(String value) { this.text = value; return this; }
    public EmailMessage replyTo(String value) { this.replyTo = value; return this; }
    public EmailMessage stream(String value) { this.stream = value; return this; }
    public EmailMessage tags(Map<String, String> value) { this.tags = value; return this; }
    public EmailMessage headers(Map<String, String> value) { this.headers = value; return this; }
    public EmailMessage templateId(String value) { this.templateId = value; return this; }
    public EmailMessage templateData(Map<String, String> value) { this.templateData = value; return this; }
    public EmailMessage testMode(boolean value) { this.testMode = value; return this; }
    public EmailMessage cc(List<String> value) { this.cc = value; return this; }
    public EmailMessage bcc(List<String> value) { this.bcc = value; return this; }
    public EmailMessage scheduledAt(String value) { this.scheduledAt = value; return this; }
    public EmailMessage topic(String value) { this.topic = value; return this; }
    public EmailMessage attachments(List<Attachment> value) { this.attachments = value; return this; }
}
