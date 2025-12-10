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
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;

import frontend.data.Sms;
import jakarta.servlet.http.HttpServletRequest;

@Controller
public class FrontendController {

    private final RestTemplateBuilder rest;
    private final String modelHost;
    // Gauges
    private final AtomicInteger inflight = new AtomicInteger(0);
    private final AtomicInteger backendStatus = new AtomicInteger(1);  // 1=UP, 0=DOWN

    // Counters
    private final AtomicLong validationErrorsEmpty = new AtomicLong(0);
    private final AtomicLong verdictHam = new AtomicLong(0);
    private final AtomicLong verdictSpam = new AtomicLong(0);

    // Histogram: latency (seconds)
    private final double[] latencyBuckets = {0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5};
    private final AtomicLong[] latencyBucketCounts = new AtomicLong[latencyBuckets.length + 1]; // +Inf bucket
    private final AtomicLong latencyCount = new AtomicLong(0);        
    private final AtomicLong latencySumMicros = new AtomicLong(0);    

    // Histogram: word count
    private final double[] wcBuckets = {1, 5, 10, 20, 50, 100};
    private final AtomicLong[] wcBucketCounts = new AtomicLong[wcBuckets.length + 1]; // +Inf
    private final AtomicLong wcCount = new AtomicLong(0);      
    private final AtomicLong wcSum = new AtomicLong(0);        

    public FrontendController(RestTemplateBuilder rest, Environment env) {
        this.rest = rest;

        this.modelHost = env.getProperty("MODEL_HOST", "").trim();
        if (!modelHost.startsWith("http")) {
            throw new RuntimeException("MODEL_HOST must include protocol (e.g., http://sms-backend-svc:8081)");
        }

        for (int i = 0; i < latencyBucketCounts.length; i++)
            latencyBucketCounts[i] = new AtomicLong(0);

        for (int i = 0; i < wcBucketCounts.length; i++)
            wcBucketCounts[i] = new AtomicLong(0);
    }


    @GetMapping("/metrics", produces = org.springframework.http.MediaType.TEXT_PLAIN_VALUE)
    public org.springframework.http.ResponseEntity<byte[]> metrics() {

        StringBuilder sb = new StringBuilder();

        // Gauges
        sb.append("# HELP frontend_inflight_requests Number of active requests\n");
        sb.append("# TYPE frontend_inflight_requests gauge\n");
        sb.append("frontend_inflight_requests ").append(inflight.get()).append("\n\n");

        sb.append("# HELP frontend_backend_status Backend health (1=up,0=down)\n");
        sb.append("# TYPE frontend_backend_status gauge\n");
        sb.append("frontend_backend_status ").append(backendStatus.get()).append("\n\n");


        // Counters
        sb.append("# HELP sms_validation_error_total Count of validation errors\n");
        sb.append("# TYPE sms_validation_error_total counter\n");
        sb.append("sms_validation_error_total{reason=\"empty\"} ")
          .append(validationErrorsEmpty.get()).append("\n\n");

        sb.append("# HELP sms_verdict_total SMS verdict count by label\n");
        sb.append("# TYPE sms_verdict_total counter\n");
        sb.append("sms_verdict_total{verdict=\"ham\"} ").append(verdictHam.get()).append("\n");
        sb.append("sms_verdict_total{verdict=\"spam\"} ").append(verdictSpam.get()).append("\n\n");


        // Histogram (Latency)
        sb.append("# HELP frontend_prediction_latency_seconds End-to-end latency\n");
        sb.append("# TYPE frontend_prediction_latency_seconds histogram\n");

        long cumulative = 0;
        for (int i = 0; i < latencyBuckets.length; i++) {
            cumulative += latencyBucketCounts[i].get();
            sb.append("frontend_prediction_latency_seconds_bucket{le=\"")
              .append(latencyBuckets[i]).append("\"} ").append(cumulative).append("\n");
        }
        cumulative += latencyBucketCounts[latencyBuckets.length].get();

        sb.append("frontend_prediction_latency_seconds_bucket{le=\"+Inf\"} ")
          .append(cumulative).append("\n");

        sb.append("frontend_prediction_latency_seconds_sum ")
          .append(latencySumMicros.get() / 1_000_000.0).append("\n"); // convert µs → seconds

        sb.append("frontend_prediction_latency_seconds_count ")
          .append(latencyCount.get()).append("\n\n");


        sb.append("# HELP sms_input_word_count Word count distribution\n");
        sb.append("# TYPE sms_input_word_count histogram\n");

        long wcCum = 0;
        for (int i = 0; i < wcBuckets.length; i++) {
            wcCum += wcBucketCounts[i].get();
            sb.append("sms_input_word_count_bucket{le=\"")
              .append(wcBuckets[i]).append("\"} ").append(wcCum).append("\n");
        }
        wcCum += wcBucketCounts[wcBuckets.length].get();

        sb.append("sms_input_word_count_bucket{le=\"+Inf\"} ")
          .append(wcCum).append("\n");
        sb.append("sms_input_word_count_sum ").append(wcSum.get()).append("\n");
        sb.append("sms_input_word_count_count ").append(wcCount.get()).append("\n");


        String metricsString = sb.toString().trim();

        byte[] body = metricsString.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        return org.springframework.http.ResponseEntity
                .ok()
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(body);
    }

    @GetMapping("/sms")
    public String redirectSlash(HttpServletRequest req) {
        return "redirect:" + req.getRequestURI() + "/";
    }

    @GetMapping("/sms/")
    public String index(Model m) {
        m.addAttribute("hostname", modelHost);
        return "sms/index";
    }

  
    @PostMapping({"/sms", "/sms/"})
    @ResponseBody
    public Sms predict(@RequestBody Sms sms) {

        inflight.incrementAndGet();
        long start = System.nanoTime();

        try {

            if (sms.sms == null || sms.sms.trim().isEmpty()) {
                validationErrorsEmpty.incrementAndGet();
                sms.result = "error: empty";
                return sms;
            }

            // Word Count Hist
            int wc = sms.sms.trim().split("\\s+").length;
            wcSum.addAndGet(wc);
            wcCount.incrementAndGet();

            boolean wcPlaced = false;
            for (int i = 0; i < wcBuckets.length; i++) {
                if (wc <= wcBuckets[i]) {
                    wcBucketCounts[i].incrementAndGet();
                    wcPlaced = true;
                    break;
                }
            }
            if (!wcPlaced)
                wcBucketCounts[wcBuckets.length].incrementAndGet(); // +Inf bucket


            // Backend Prediction
            try {
                var url = new URI(modelHost + "/predict");
                var resp = rest.build().postForEntity(url, sms, Sms.class);
                sms.result = resp.getBody().result.trim();
                backendStatus.set(1);

                if (sms.result.equalsIgnoreCase("ham")) verdictHam.incrementAndGet();
                else verdictSpam.incrementAndGet();

            } catch (Exception e) {
                backendStatus.set(0);
                sms.result = "error: backend unavailable";
            }

            return sms;

        } finally {
            // Latency histogram
            long durNanos = System.nanoTime() - start;
            long durMicros = durNanos / 1000;
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
            if (!placed)
                latencyBucketCounts[latencyBuckets.length].incrementAndGet(); // +Inf

            inflight.decrementAndGet();
        }
    }
}
