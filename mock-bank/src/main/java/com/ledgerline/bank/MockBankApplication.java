package com.ledgerline.bank;

import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MockBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockBankApplication.class, args);
    }

    @Bean
    RandomGenerator random() {
        return new Random(); // thread-safe, unlike SplittableRandom
    }

    @Bean
    Sleeper sleeper() {
        return Sleeper.real();
    }
}
