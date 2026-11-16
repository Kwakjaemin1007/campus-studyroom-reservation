package com.studyroom.app.config;

import com.studyroom.StudyRoomSystem;
import com.studyroom.boundary.AdminPolicyApi;
import com.studyroom.boundary.StudentReservationApi;
import com.studyroom.control.availability.OutboxRelay;
import com.studyroom.control.modifiable.PolicySwitchManager;
import com.studyroom.device.modifiable.QrCheckInAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Duration;

/** 기본 프로필: 코어의 메모리 조립(StudyRoomSystem)을 그대로 띄운다. AWS가 필요 없다. */
@Configuration
@Profile("!aws")
@EnableConfigurationProperties(StudyRoomProperties.class)
public class LocalConfig {
    @Bean(destroyMethod = "close")
    StudyRoomSystem studyRoomSystem(StudyRoomProperties props) {
        StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), props.toSystemConfig()).start();
        sys.scheduler.start(Duration.ofSeconds(1));
        return sys;
    }

    @Bean
    StudentReservationApi studentReservationApi(StudyRoomSystem sys) {
        return sys.studentApi;
    }

    @Bean
    AdminPolicyApi adminPolicyApi(StudyRoomSystem sys) {
        return sys.adminApi;
    }

    @Bean
    QrCheckInAdapter qrCheckInAdapter(StudyRoomSystem sys) {
        return sys.qrDevice;
    }

    @Bean
    PolicySwitchManager policySwitchManager(StudyRoomSystem sys) {
        return sys.policySwitch;
    }

    @Bean
    OutboxRelay outboxRelay(StudyRoomSystem sys) {
        return sys.outboxRelay;
    }
}
