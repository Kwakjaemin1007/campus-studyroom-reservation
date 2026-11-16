package com.studyroom.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class StudyRoomApplication {
    public static void main(String[] args) {
        // JDBC 타임스탬프를 기계 시간대와 무관하게 UTC로 저장·해석한다(schema.sql 주석 참고).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(StudyRoomApplication.class, args);
    }
}
