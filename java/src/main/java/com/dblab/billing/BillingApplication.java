package com.dblab.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The program's starting point (Stage 2, lesson A2).
 *
 * <p>{@code @SpringBootApplication} tells Spring Boot to scan this package and the packages below it for
 * classes it should create (services, repositories, configuration), and to configure everything it finds on
 * the classpath: the connection pool, Flyway, Hibernate. There is no web server: the program starts, runs
 * the migrations, reports what it found, and stops.
 */
@SpringBootApplication
public class BillingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}