package com.studyroom.app.web;

import com.studyroom.control.availability.api.IReservationLifecycle.TransitionResult;
import com.studyroom.device.modifiable.QrCheckInAdapter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** 현장 QR 기기가 스캔 결과를 보내는 웹훅. 기기 형식의 해석은 QrCheckInAdapter가 한다. */
@RestController
public class QrDeviceController {
    public record ScanBody(String payload) {
    }

    private final QrCheckInAdapter adapter;

    public QrDeviceController(QrCheckInAdapter adapter) {
        this.adapter = adapter;
    }

    @PostMapping("/devices/qr/scan")
    public ResponseEntity<Object> scan(@RequestBody ScanBody body) {
        TransitionResult result;
        try {
            result = adapter.onRawScan(body.payload(), Instant.now());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("checkedIn", result == TransitionResult.APPLIED, "result", String.valueOf(result)));
    }
}
