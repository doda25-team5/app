package frontend.ctrl;

import com.github.doda25_team5.lib_version.VersionUtil
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController {

    @GetMapping("/lib-version")
    public String getLibVersion() {
        return VersionUtil.getVersion();
    }
}
