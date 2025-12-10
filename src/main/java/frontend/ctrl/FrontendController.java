package frontend.ctrl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import frontend.data.Sms;
import jakarta.servlet.http.HttpServletRequest;


import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;

@Controller
// NOTE: I removed @RequestMapping("/sms") from here so we can have /metrics at the root
public class FrontendController {

    private String modelHost;
    private RestTemplateBuilder rest;
    

    private final PrometheusMeterRegistry registry; 

    private final AtomicInteger inflightRequests;
    private final AtomicInteger backendStatus;


    private final Timer latencyTimer;
    private final DistributionSummary wordCountSummary;

    public FrontendController(RestTemplateBuilder rest, Environment env, PrometheusMeterRegistry registry) {
        this.rest = rest;
        this.modelHost = env.getProperty("MODEL_HOST");
        this.registry = registry;
        assertModelHost();


        this.inflightRequests = new AtomicInteger(0);
        Gauge.builder("frontend_inflight_requests", inflightRequests, AtomicInteger::get)
                .description("Number of requests currently being processed")
                .register(registry);

        this.backendStatus = new AtomicInteger(1);
        Gauge.builder("frontend_backend_status", backendStatus, AtomicInteger::get)
                .description("Status of connection to model-service")
                .register(registry);

        this.latencyTimer = Timer.builder("frontend_prediction_latency_seconds")
                .description("End-to-end prediction time")
                .publishPercentileHistogram()
                .register(registry);

        this.wordCountSummary = DistributionSummary.builder("sms_input_word_count")
                .description("Word count distribution")
                .baseUnit("words")
                .publishPercentileHistogram()
                .register(registry);
    }


    @GetMapping("/metrics")
    @ResponseBody
    public String metrics() {

        return registry.scrape();
    }

    private void assertModelHost() {
        if (modelHost == null || modelHost.strip().isEmpty()) {
            System.err.println("ERROR: ENV variable MODEL_HOST is null");
            System.exit(1);
        }
        modelHost = modelHost.strip();
        if (modelHost.indexOf("://") == -1) {
            System.err.printf("ERROR: MODEL_HOST missing protocol (was: \"%s\")\n", modelHost);
            System.exit(1);
        }
    }

    @GetMapping("/sms")
    public String redirectToSlash(HttpServletRequest request) {
        return "redirect:" + request.getRequestURI() + "/";
    }

    @GetMapping("/sms/")
    public String index(Model m) {
        m.addAttribute("hostname", modelHost);
        return "sms/index";
    }

    @PostMapping({ "/sms", "/sms/" })
    @ResponseBody
    public Sms predict(@RequestBody Sms sms) {
        inflightRequests.incrementAndGet(); // Gauge Up

        // Metric: Validation Error
        if (sms.sms == null || sms.sms.trim().isEmpty()) {
            Counter.builder("sms_validation_error_total")
                   .tag("reason", "empty").register(registry).increment();
            inflightRequests.decrementAndGet();
            sms.result = "error: empty";
            return sms;
        }

        // Metric: Word Count
        wordCountSummary.record(sms.sms.trim().split("\\s+").length);

        try {
            return latencyTimer.record(() -> {
                try {
                    sms.result = getPrediction(sms);
                    backendStatus.set(1); 
                    
                    Counter.builder("sms_verdict_total")
                           .tag("verdict", sms.result.toLowerCase())
                           .register(registry).increment();
                           
                } catch (Exception e) {
                    backendStatus.set(0); // Backend Down
                    throw e;
                }
                return sms;
            });
        } finally {
            inflightRequests.decrementAndGet(); // Gauge Down
        }
    }

    private String getPrediction(Sms sms) {
        try {
            var url = new URI(modelHost + "/predict");
            var c = rest.build().postForEntity(url, sms, Sms.class);
            return c.getBody().result.trim();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}