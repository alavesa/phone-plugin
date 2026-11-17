package fi.alavesa.phone.resourcepack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP host for the pack (the JDK's built-in server, its own port). Serves exactly one path,
 * /phone/&lt;sha1&gt;.zip; everything else is 404. Runs on two daemon threads, never on the main thread.
 */
final class PackHttpServer {

    private final HttpServer server;
    private final ExecutorService pool;
    private final byte[] zip;
    private final String path;

    PackHttpServer(String bind, int port, byte[] zip, String sha1) throws IOException {
        this.zip = zip;
        this.path = "/phone/" + sha1 + ".zip";
        server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "phone-pack-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    String path() {
        return path;
    }

    void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            boolean get = ex.getRequestMethod().equals("GET");
            boolean head = ex.getRequestMethod().equals("HEAD");
            if ((!get && !head) || !ex.getRequestURI().getPath().equals(path)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            if (head) {
                ex.getResponseHeaders().set("Content-Length", String.valueOf(zip.length));
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, zip.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(zip);
            }
        }
    }
}
