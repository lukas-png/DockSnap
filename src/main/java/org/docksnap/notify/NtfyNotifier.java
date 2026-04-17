package org.docksnap.notify;

import org.docksnap.domain.Job;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class NtfyNotifier {

    private final String baseUrl;
    private final HttpClient http;

    public NtfyNotifier(String baseUrl) {
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank())
                ? baseUrl.replaceAll("/+$", "") : null;
        this.http = baseUrl != null ? HttpClient.newHttpClient() : null;
    }

    public void notifySuccess(Job job, String archive) {
        send(job, "Backup complete: " + archive, "white_check_mark", "3");
    }

    public void notifyFailure(Job job, String error) {
        send(job, "Backup failed: " + error, "warning", "4");
    }

    private void send(Job job, String body, String tags, String priority) {
        if (baseUrl == null) return;
        if (job.ntfyTopic() == null || job.ntfyTopic().isBlank()) return;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/" + job.ntfyTopic()))
                    .header("X-Title", "DockSnap - " + job.name())
                    .header("X-Priority", priority)
                    .header("X-Tags", tags)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) {
                System.err.printf("[WARN][Notify] ntfy returned HTTP %d for job %s%n",
                        resp.statusCode(), job.id());
            }
        } catch (Exception e) {
            System.err.printf("[WARN][Notify] Failed to send ntfy notification for job %s: %s%n",
                    job.id(), e.getMessage());
        }
    }
}
