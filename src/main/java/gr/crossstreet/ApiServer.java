package gr.crossstreet;

import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import gr.crossstreet.model.DetectionResult;
import gr.crossstreet.model.GeoPoint;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    public static void main(String[] args) {
        int port = 8080;
        if (args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0] + ". Usage: ApiServer [port]");
                System.exit(1);
            }
        }

        CrossStreetDetectorApp app = new CrossStreetDetectorApp();

        Javalin server = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
                mapper.registerModule(new Jdk8Module());
            }));
        });

        server.get("/detect", ctx -> {
            String prev = ctx.queryParam("prev");
            String curr = ctx.queryParam("curr");

            if (prev == null || prev.isBlank() || curr == null || curr.isBlank()) {
                ctx.status(400).json(new ErrorResponse("Missing required query params: 'prev' and 'curr'. " +
                        "Example: /detect?prev=37.988891,23.741949&curr=37.988988,23.741867"));
                return;
            }

            GeoPoint previous;
            GeoPoint current;
            try {
                previous = GeoPoint.parse(prev);
                current = GeoPoint.parse(curr);
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(new ErrorResponse("Invalid coordinate format: " + e.getMessage()));
                return;
            }

            try {
                DetectionResult result = app.detect(current, previous);
                ctx.json(result);
            } catch (Exception e) {
                log.error("Detection failed", e);
                ctx.status(500).json(new ErrorResponse("Detection failed: " + e.getMessage()));
            }
        });

        server.get("/health", ctx -> ctx.json(new HealthResponse("ok")));

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start(port);
        log.info("Cross-street detector API started on port {}", port);
    }

    record ErrorResponse(String error) {}
    record HealthResponse(String status) {}
}