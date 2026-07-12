package com.joopapa.familyalbum.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

    @Bean
    S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .endpointOverride(URI.create(properties.endpoint()))
                .build();
    }

    @Bean
    S3Presigner s3Presigner(StorageProperties properties) {
        return S3Presigner.builder()
                .region(Region.of(properties.region()))
                .endpointOverride(URI.create(properties.endpoint()))
                .build();
    }
}
