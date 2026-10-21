package ro.tinulica.tvhome;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny web page on the TV, open only while a question is waiting, so it can be answered from a
 * phone on the same Wi-Fi: the QR code on the card opens http://TV:8642/q/ID, which shows the
 * question and its choices; a choice is /a/ID/N. The ID is random, so only someone who sees the
 * TV's screen can answer.
 */
final class QuestionServer {

    static final int PORT = 8642;

    interface Listener {
        void answered(String id, int index, String label);
    }

    static final class Question {
        String id;
        String from;
        String title;
        String text;
        String details;
        List<String> options;
        volatile String answer;
    }

    private final Map<String, Question> open = new ConcurrentHashMap<>();
    private final Listener listener;
    private ServerSocket socket;
    private Thread thread;

    QuestionServer(Listener listener) {
        this.listener = listener;
    }

    synchronized void ask(Question q) {
        open.put(q.id, q);
        if (thread == null) {
            thread = new Thread(this::serve, "questions");
            thread.setDaemon(true);
            thread.start();
        }
    }

    synchronized void close(String id) {
        open.remove(id);
        if (open.isEmpty()) {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (Exception ignored) {
                // Closed already.
            }
            socket = null;
            thread = null;
        }
    }

    private void serve() {
        try (ServerSocket s = new ServerSocket(PORT)) {
            synchronized (this) {
                socket = s;
            }
            while (!s.isClosed()) {
                final Socket client = s.accept();
                new Thread(() -> handle(client), "question").start();
            }
        } catch (Exception ignored) {
            // Closed: no question waiting any more.
        }
        synchronized (this) {
            thread = null;
        }
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) {
                return;
            }
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String[] p = path.split("/");
            String body;
            int status = 200;
            if (p.length >= 3 && "q".equals(p[1]) && open.containsKey(p[2])) {
                body = page(open.get(p[2]), null);
            } else if (p.length >= 4 && "a".equals(p[1]) && open.containsKey(p[2])) {
                Question q = open.get(p[2]);
                int index = -1;
                try {
                    index = Integer.parseInt(p[3]);
                } catch (NumberFormatException ignored) {
                    // Not a number.
                }
                if (index >= 0 && index < q.options.size()) {
                    String label = q.options.get(index);
                    body = page(q, label);
                    listener.answered(q.id, index, label);
                } else {
                    body = page(q, null);
                }
            } else {
                status = 404;
                body = shell("<p class=muted>Întrebarea nu mai e deschisă.</p>");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = c.getOutputStream();
            out.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Not Found") + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n"
                    + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (Exception ignored) {
            // The phone went away.
        }
    }

    private static String page(Question q, String chosen) {
        StringBuilder b = new StringBuilder();
        b.append("<div class=from>").append(esc(q.from == null || q.from.isEmpty() ? "Claude" : q.from)).append("</div>");
        b.append("<h1>").append(esc(q.title)).append("</h1>");
        if (q.text != null && !q.text.isEmpty()) {
            b.append("<p>").append(esc(q.text)).append("</p>");
        }
        if (q.details != null && !q.details.isEmpty()) {
            b.append("<div class=details>").append(esc(q.details).replace("\n", "<br>")).append("</div>");
        }
        String answered = chosen != null ? chosen : q.answer;
        if (answered != null) {
            b.append("<div class=done>✓ ").append(esc(answered)).append("</div><p class=muted>Gata. Poți închide pagina.</p>");
        } else {
            for (int i = 0; i < q.options.size(); i++) {
                b.append("<a class=choice href=\"/a/").append(q.id).append('/').append(i).append("\">")
                        .append(esc(q.options.get(i))).append("</a>");
            }
        }
        return shell(b.toString());
    }

    private static String shell(String inner) {
        return "<!doctype html><html lang=ro><head><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width,initial-scale=1\"><title>TV Home</title><style>"
                + "body{margin:0;min-height:100vh;background:#141417;color:#f4f1ee;font:16px/1.45 -apple-system,"
                + "system-ui,Segoe UI,Roboto,sans-serif;display:flex;justify-content:center}"
                + "main{max-width:520px;width:100%;padding:28px 20px 40px;box-sizing:border-box}"
                + ".from{color:#f0a58a;letter-spacing:.08em;text-transform:uppercase;font-size:13px;font-weight:600}"
                + "h1{font-size:24px;line-height:1.25;margin:8px 0 10px}p{margin:0 0 12px;opacity:.92}"
                + ".details{background:#1f1f23;border-radius:14px;padding:14px 16px;margin:6px 0 18px;opacity:.9;font-size:15px}"
                + ".choice{display:block;text-align:center;text-decoration:none;color:#141417;background:#f4f1ee;"
                + "border-radius:14px;padding:15px;margin:10px 0;font-weight:600;font-size:17px}"
                + ".choice:first-of-type{background:#d97757;color:#fff}"
                + ".done{color:#f0a58a;font-size:20px;font-weight:600;margin:18px 0 6px}.muted{opacity:.6}"
                + "</style></head><body><main>" + inner + "</main></body></html>";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
