package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.api.SwitchStatus;
import org.junit.Test;

import java.time.Clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 전환 기록은 PolicySwitchManager가 아니라 ISwitchStateStore(Device)에 남는다. 그래서 다른 서버에서도 조회된다. */
public class SwitchRecordTest {
    @Test
    public void switchStatusIsStoredInDeviceStore() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(TestSupport.liveClock(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            ApiResponse<String> res = sys.adminApi.switchPolicy("admin:root", CreditBettingPolicy.NAME);
            assertEquals(202, res.status());
            String id = res.body();
            TestSupport.await(() -> sys.switchState.findStatus(id).map(s -> s.state() == SwitchStatus.State.COMPLETED).orElse(false), 5000);
            assertTrue("저장소에 기록이 있다", sys.switchState.findStatus(id).isPresent());
            assertEquals("관리자 화면이 보는 값과 저장소 값이 같다",
                    sys.switchState.findStatus(id).orElseThrow(), sys.policySwitch.status(id).orElseThrow());
        }
    }
}
