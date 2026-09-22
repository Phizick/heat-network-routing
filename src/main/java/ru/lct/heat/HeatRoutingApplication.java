package ru.lct.heat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HeatRoutingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeatRoutingApplication.class, args);
    }
}
