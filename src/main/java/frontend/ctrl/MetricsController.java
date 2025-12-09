package frontend.ctrl;

// import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
public class MetricsController {

    private final RestTemplate rest = new RestTemplate();

    @GetMapping("/metrics")
    @ResponseBody
    public String metrics() {
        // Spring Boot Actuator already exposes real Prometheus metrics here
        String actuatorUrl = "http://localhost:8080/actuator/prometheus";
        return rest.getForObject(actuatorUrl, String.class);
    }
}
