package com.polarbookshop.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ClientConfig {

  @Bean
  WebClient webClient(ClientProperties cp, WebClient.Builder wcb) {
    return wcb
      .baseUrl(cp.catalogServiceUri().toString())
      .build();
  }
}
