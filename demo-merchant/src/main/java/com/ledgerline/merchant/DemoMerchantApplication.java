package com.ledgerline.merchant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DemoMerchantApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoMerchantApplication.class, args);
    }
}
