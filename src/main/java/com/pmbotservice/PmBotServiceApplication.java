package com.pmbotservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PmBotServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PmBotServiceApplication.class, args);
  }
}
