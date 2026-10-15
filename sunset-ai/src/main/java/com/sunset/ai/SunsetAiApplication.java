package com.sunset.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.sunset.ai.mapper")
public class SunsetAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SunsetAiApplication.class, args);
    }

}
