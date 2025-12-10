package frontend.ctrl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import frontend.data.Sms;
import jakarta.servlet.http.HttpServletRequest;

@Controller
public class FrontendController {

    private final RestTemplateBuilder rest;
    private final String modelHost;

    // gauge: active sessions
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    // counter: total prediction requests
    private final AtomicLong smsRequestsTotal = new AtomicLong(0);

    // counter: prediction results by category
    private final AtomicLong spamResult = new AtomicLong(0);
    private final AtomicLong hamResult = new AtomicLong(0);

    // histogram: request latency in seconds
    private final double[] latencyBuckets = {0.05, 0.1, 0.25, 0.5, 1, 2, 5};
    private final AtomicLong[] latencyBucketCounts = new AtomicLong[latencyBuckets.length + 1];
    private final AtomicLong latencyCount = new AtomicLong(0);
    private final AtomicLong latencySumMicros = new AtomicLong(0);

    // histogram: sms input length in characters
    private final double[] lengthBuckets = {20, 50, 100, 160};
    private final AtomicLong[] lengthBucketCounts = new AtomicLong[lengthBuckets.length + 1];
    private final AtomicLong lengthCount = new AtomicLong(0);
    private final AtomicLong lengthSum = new AtomicLong(0);


    public FrontendController(RestTemplateBuilder rest, Environment env) {
        this.rest = rest;

        this.modelHost = env.getProperty("MODEL_HOST", "").trim();
        if (!modelHost.startsWith("http")) {
            throw new RuntimeException("model host must include protocol (e.g., http://sms-backend-svc:8081)");
        }

        for (int i = 0; i < latencyBucketCounts.length; i++)
            latencyBucketCounts[i] = new AtomicLong(0);

        for (int i = 0; i < lengthBucketCounts.length; i++)
            lengthBucketCounts[i] = new AtomicLong(0);
    }


    @GetMapping(path = "/metrics", produces = "text/plain")
    @ResponseBody
    public String metrics() {
        StringBuilder sb = new StringBuilder();

        // gauge: active sessions
        sb.append("# type frontend_active_sessions gauge\n");
        sb.append("frontend_active_sessions ").append(activeSessions.get()).append("\n\n");

        // counter: total requests
        sb.append("# type sms_requests_total counter\n");
        sb.append("sms_requests_total ").append(smsRequestsTotal.get()).append("\n\n");

        // counters: prediction result totals
        sb.append("# type sms_prediction_result_total counter\n");
        sb.append("sms_prediction_result_total{category=\"ham\"} ").append(hamResult.get()).append("\n");
        sb.append("sms_prediction_result_total{category=\"spam\"} ").append(spamResult.get()).append("\n\n");

        // histogram: latency
        sb.append("# type frontend_request_latency_seconds histogram\n");
        long cum = 0;
        for (int i = 0; i < latencyBuckets.length; i++) {
            cum += latencyBucketCounts[i].get();
            sb.append("frontend_request_latency_seconds_bucket{le=\"")
              .append(latencyBuckets[i]).append("\"} ").append(cum).append("\n");
        }
        cum += latencyBucketCounts[latencyBuckets.length].get();
        sb.append("frontend_request_latency_seconds_bucket{le=\"+Inf\"} ").append(cum).append("\n");

        sb.append("frontend_request_latency_seconds_sum ")
          .append(latencySumMicros.get() / 1_000_000.0).append("\n");
        sb.append("frontend_request_latency_seconds_count ")
          .append(latencyCount.get()).append("\n\n");

        // histogram: sms input length
        sb.append("# type sms_input_length histogram\n");
        long lenCum = 0;
        for (int i = 0; i < lengthBuckets.length; i++) {
            lenCum += lengthBucketCounts[i].get();
            sb.append("sms_input_length_bucket{le=\"")
              .append(lengthBuckets[i]).append("\"} ").append(lenCum).append("\n");
        }
        lenCum += lengthBucketCounts[lengthBuckets.length].get();
        sb.append("sms_input_length_bucket{le=\"+Inf\"} ").append(lenCum).append("\n");

        sb.append("sms_input_length_sum ").append(lengthSum.get()).append("\n");
        sb.append("sms_input_length_count ").append(lengthCount.get()).append("\n");

        return sb.toString().trim() + "\n";
    }


    @GetMapping("/sms")
    public String redirectSlash(HttpServletRequest req) {
        return "redirect:" + req.getRequestURI() + "/";
    }

    @GetMapping("/sms/")
    public String index(Model m) {
        activeSessions.incrementAndGet();
        m.addAttribute("hostname", modelHost);
        return "sms/index";
    }


    @PostMapping({"/sms", "/sms/"})
    @ResponseBody
    public Sms predict(@RequestBody Sms sms) {

        smsRequestsTotal.incrementAndGet();

        long start = System.nanoTime();

        try {
            int length = sms.sms == null ? 0 : sms.sms.length();
            lengthSum.addAndGet(length);
            lengthCount.incrementAndGet();

            boolean placed = false;
            for (int i = 0; i < lengthBuckets.length; i++) {
                if (length <= lengthBuckets[i]) {
                    lengthBucketCounts[i].incrementAndGet();
                    placed = true;
                    break;
                }
            }
            if (!placed) lengthBucketCounts[lengthBuckets.length].incrementAndGet();

            String result;
            try {
                var url = new URI(modelHost + "/predict");
                var resp = rest.build().postForEntity(url, sms, Sms.class);
                result = resp.getBody().result.trim();
            } catch (Exception e) {
                result = "error";
            }

            if ("spam".equalsIgnoreCase(result)) spamResult.incrementAndGet();
            else hamResult.incrementAndGet();

            sms.result = result;
            return sms;

        } finally {
            long durMicros = (System.nanoTime() - start) / 1000;
            double seconds = durMicros / 1_000_000.0;

            latencySumMicros.addAndGet(durMicros);
            latencyCount.incrementAndGet();

            boolean placed = false;
            for (int i = 0; i < latencyBuckets.length; i++) {
                if (seconds <= latencyBuckets[i]) {
                    latencyBucketCounts[i].incrementAndGet();
                    placed = true;
                    break;
                }
            }
            if (!placed) latencyBucketCounts[latencyBuckets.length].incrementAndGet();
        }
    }
}