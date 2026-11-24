package io.smarteval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@EnableScheduling
@SpringBootApplication
public class SmartEval {
    public static void main(String[] args) {
        SpringApplication.run(SmartEval.class, args);
    }
}

@RestController
class BusinessHealth {
    private final ApplicationAvailability availability;

    BusinessHealth(ApplicationAvailability availability) { this.availability = availability; }

    @GetMapping("/health")
    ResponseEntity<Void> health() {
        return ResponseEntity.status(availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC ? 204 : 503).build();
    }
}
