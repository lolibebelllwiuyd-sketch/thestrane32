package me.zein.zeinac.alert;

import me.zein.zeinac.ZeinAC;
import me.zein.zeinac.config.Settings;
import me.zein.zeinac.model.Alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Optional Discord-compatible webhook. Fully asynchronous, rate limited, and fails silently (one warning per minute). */
public final class WebhookNotifier {
    private final ZeinAC plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicLong lastSendMs = new AtomicLong();
    private volatile long lastWarnMs;

    public WebhookNotifier(ZeinAC plugin) {
        this.plugin = plugin;
    }

    public void send(Alert a) {
        Settings.Webhook w = plugin.settings().webhook;
        if (!w.enabled || w.url == null || !w.url.startsWith("https://") || !a.confidence().atLeast(w.minConfidence)) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastSendMs.get();
        if (now - last < 1500L || !lastSendMs.compareAndSet(last, now)) {
            return; // rate limit: drop instead of queueing
        }
        String text = "**ZeinAC** " + a.name() + " " + a.module() + "/" + a.type() + " " + a.confidence()
                + " score=" + String.format(java.util.Locale.ROOT, "%.1f", a.score())
                + " @" + a.world() + " " + a.x() + " " + a.y() + " " + a.z() + "\n" + a.detail();
        if (text.length() > 1800) {
            text = text.substring(0, 1800);
        }
        String json = "{\"content\":\"" + escape(text) + "\"}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(w.url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            client.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((resp, err) -> {
                if (err != null || (resp != null && resp.statusCode() >= 300)) {
                    warn(err != null ? err.toString() : "HTTP " + resp.statusCode());
                }
            });
        } catch (IllegalArgumentException e) {
            warn("invalid webhook url");
        }
    }

    private void warn(String why) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs > 60_000L) {
            lastWarnMs = now;
            plugin.getLogger().warning("Webhook delivery failed: " + why);
        }
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
