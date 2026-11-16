package com.plantorganizer.common;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * One shared HTTP request factory for every outgoing API call, so all the
 * adapters use the same connect/read timeouts. Without a read timeout a hung
 * external service would hang the user's request forever.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public ClientHttpRequestFactory externalApiRequestFactory(
            @Value("${plantorganizer.http.connect-timeout-seconds:10}") long connectTimeout,
            @Value("${plantorganizer.http.read-timeout-seconds:60}") long readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeout));
        return factory;
    }
}
