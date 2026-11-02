import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

// Run: java Hospital.java   then open http://localhost:8080
// ponytail: in-memory store, data resets on restart; swap for a file/DB
// when it must persist.
// Default HttpServer executor is single-threaded, so plain collections are safe.
public class Hospital {
    static final Map<String, String> USERS =
            Map.of("admin", "admin123", "reception", "reception123");
    static final Map<String, String> SESSIONS = new HashMap<>();
    static final SecureRandom RNG = new SecureRandom();

    // entity -> "Label|type". type: html input type, "a,b,c" for fixed options,
    // "@entity" to pick from another entity
    static final Map<String, String[]> FIELDS = new LinkedHashMap<>();
    static final Map<String, List<Map<String, String>>> DATA = new HashMap<>();
    static int nextId = 1;

    public static void main(String[] args) throws IOException {
        FIELDS.put("patients", new String[]{"Name|text", "Age|number",
                "Gender|Male,Female,Other", "Phone|tel", "Condition|text"});
        FIELDS.put("doctors", new String[]{"Name|text", "Specialty|text", "Phone|tel"});
        FIELDS.put("appointments", new String[]{"Patient|@patients",
                "Doctor|@doctors", "Date|date", "Time|time", "Reason|text"});
        FIELDS.keySet().forEach(e -> DATA.put(e, new ArrayList<>()));

        String today = LocalDate.now().toString();
        add("doctors", "Dr. Anita Rao", "Cardiology", "9876500001");
        add("doctors", "Dr. Vikram Shah", "Orthopedics", "9876500002");
        add("doctors", "Dr. Meera Iyer", "Pediatrics", "9876500003");
        add("patients", "Rahul Kumar", "45", "Male", "9123400001", "Hypertension");
        add("patients", "Priya Nair", "32", "Female", "9123400002", "Knee pain");
        add("patients", "Aarav Singh", "7", "Male", "9123400003", "Fever");
        add("appointments", "Rahul Kumar", "Dr. Anita Rao", today, "10:00",
                "BP check-up");
        add("appointments", "Aarav Singh", "Dr. Meera Iyer", today, "11:30",
                "Follow-up");

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", Hospital::handle);
        server.start();
        System.out.println("Hospital app running at http://localhost:8080"
                + "  (login: admin / admin123)");
    }

    static void add(String entity, String... values) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("id", String.valueOf(nextId++));
        String[] fields = FIELDS.get(entity);
        for (int i = 0; i < fields.length; i++) row.put(label(fields[i]), values[i]);
        DATA.get(entity).add(row);
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        boolean post = ex.getRequestMethod().equals("POST");
        String body = post ? new String(ex.getRequestBody().readAllBytes(), UTF_8) : null;
        Map<String, String> form = parse(body);

        if (path.equals("/style.css")) {
            send(ex, 200, "text/css", Files.readString(Path.of("style.css")));
            return;
        }
        if (path.equals("/login")) {
            String user = form.get("username"), pass = form.get("password");
            if (post && pass != null && pass.equals(USERS.get(user))) {
                String token = new BigInteger(130, RNG).toString(32);
                SESSIONS.put(token, user);
                // SameSite=Strict also blocks cross-site form posts (CSRF)
                ex.getResponseHeaders().add("Set-Cookie",
                        "sid=" + token + "; HttpOnly; Path=/; SameSite=Strict");
                redirect(ex, "/");
            } else {
                String error = post ? "Invalid username or password" : null;
                send(ex, 200, "text/html", loginPage(error));
            }
            return;
        }

        String user = SESSIONS.get(sessionId(ex));
        if (user == null) {
            redirect(ex, "/login");
            return;
        }
        if (path.equals("/logout")) {
            SESSIONS.remove(sessionId(ex));
            redirect(ex, "/login");
            return;
        }
        if (path.equals("/")) {
            send(ex, 200, "text/html", page("Dashboard", user, dashboard()));
            return;
        }

        String[] parts = path.split("/"); // "/patients" or "/patients/delete"
        String entity = parts.length > 1 ? parts[1] : "";
        if (!FIELDS.containsKey(entity)) {
            send(ex, 404, "text/html", page("Not found", user, "<p>Page not found.</p>"));
            return;
        }
        if (post && parts.length == 3 && parts[2].equals("delete")) {
            DATA.get(entity).removeIf(r -> r.get("id").equals(form.get("id")));
            redirect(ex, "/" + entity);
            return;
        }
        String error = null;
        if (post) {
            String[] fields = FIELDS.get(entity);
            String[] values = new String[fields.length];
            for (int i = 0; i < fields.length; i++) {
                values[i] = form.getOrDefault(label(fields[i]), "");
                if (values[i].isBlank()) error = label(fields[i]) + " is required";
            }
            if (error == null) {
                add(entity, values);
                redirect(ex, "/" + entity);
                return;
            }
        }
        String html = addForm(entity, error) + table(entity, DATA.get(entity), true);
        send(ex, error == null ? 200 : 400, "text/html", page(title(entity), user, html));
    }

    static String dashboard() {
        StringBuilder sb = new StringBuilder("<div class=stats>");
        for (String e : FIELDS.keySet())
            sb.append("<a class=stat href=/").append(e).append("><b>")
                    .append(DATA.get(e).size()).append("</b>").append(title(e))
                    .append("</a>");
        String today = LocalDate.now().toString();
        List<Map<String, String>> todays = DATA.get("appointments").stream()
                .filter(r -> today.equals(r.get("Date"))).toList();
        return sb.append("</div><h2>Today's appointments</h2>")
                .append(table("appointments", todays, false)).toString();
    }

    static String addForm(String entity, String error) {
        StringBuilder sb = new StringBuilder("<form method=post class=card>");
        if (error != null) sb.append("<p class=error>").append(esc(error)).append("</p>");
        sb.append("<div class=grid>");
        for (String spec : FIELDS.get(entity)) {
            String name = label(spec), type = spec.split("\\|")[1];
            sb.append("<label>").append(name);
            if (type.startsWith("@") || type.contains(",")) {
                List<String> options = type.startsWith("@")
                        ? DATA.get(type.substring(1)).stream()
                                .map(r -> r.get("Name")).toList()
                        : List.of(type.split(","));
                sb.append("<select name=").append(name)
                        .append(" required><option value=''>Select…</option>");
                for (String o : options)
                    sb.append("<option>").append(esc(o)).append("</option>");
                sb.append("</select>");
            } else {
                sb.append("<input name=").append(name).append(" type=").append(type)
                        .append(type.equals("number") ? " min=0 max=150" : "")
                        .append(" required>");
            }
            sb.append("</label>");
        }
        return sb.append("</div><button>Add ")
                .append(title(entity).replaceAll("s$", ""))
                .append("</button></form>").toString();
    }

    static String table(String entity, List<Map<String, String>> rows,
                        boolean deletable) {
        if (rows.isEmpty()) return "<p class=muted>No records.</p>";
        StringBuilder sb = new StringBuilder("<table><tr><th>#</th>");
        for (String spec : FIELDS.get(entity))
            sb.append("<th>").append(label(spec)).append("</th>");
        if (deletable) sb.append("<th></th>");
        sb.append("</tr>");
        for (Map<String, String> r : rows) {
            sb.append("<tr>");
            r.values().forEach(v -> sb.append("<td>").append(esc(v)).append("</td>"));
            if (deletable)
                sb.append("<td><form method=post action=/").append(entity)
                        .append("/delete><input type=hidden name=id value=")
                        .append(r.get("id"))
                        .append("><button class=danger>Delete</button></form></td>");
            sb.append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    static String page(String title, String user, String body) {
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + title + " · CityCare Hospital</title>"
                + "<link rel=stylesheet href=/style.css></head><body>"
                + "<header><b>CityCare Hospital</b><nav>"
                + "<a href=/>Dashboard</a><a href=/patients>Patients</a>"
                + "<a href=/doctors>Doctors</a><a href=/appointments>Appointments</a>"
                + "<a href=/logout>Logout (" + esc(user) + ")</a></nav></header>"
                + "<main><h1>" + title + "</h1>" + body + "</main></body></html>";
    }

    static String loginPage(String error) {
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Login · CityCare Hospital</title>"
                + "<link rel=stylesheet href=/style.css></head><body class=center>"
                + "<form method=post action=/login class='card login'>"
                + "<h1>CityCare Hospital</h1>"
                + (error == null ? "" : "<p class=error>" + error + "</p>")
                + "<label>Username<input name=username required autofocus></label>"
                + "<label>Password<input name=password type=password required>"
                + "</label><button>Log in</button>"
                + "<p class=muted>Demo: <code>admin / admin123</code> or "
                + "<code>reception / reception123</code></p></form></body></html>";
    }

    static String label(String spec) { return spec.split("\\|")[0]; }

    static String title(String entity) {
        return Character.toUpperCase(entity.charAt(0)) + entity.substring(1);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static Map<String, String> parse(String body) {
        Map<String, String> m = new HashMap<>();
        if (body != null)
            for (String kv : body.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), UTF_8),
                        URLDecoder.decode(kv.substring(i + 1), UTF_8).trim());
            }
        return m;
    }

    static String sessionId(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null)
            for (String c : cookie.split(";\\s*"))
                if (c.startsWith("sid=")) return c.substring(4);
        return "";
    }

    static void send(HttpExchange ex, int code, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }
}
