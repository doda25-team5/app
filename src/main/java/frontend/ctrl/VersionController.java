package frontend.ctrl;

import org.example.libversion.VersionUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController {

    @GetMapping("/lib-version")
    public String getLibVersion() {
        return VersionUtil.getVersion();
    }
}