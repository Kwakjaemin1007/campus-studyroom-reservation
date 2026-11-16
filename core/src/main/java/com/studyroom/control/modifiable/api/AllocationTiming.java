package com.studyroom.control.modifiable.api;

/** 정책이 판정하는 시점. IMMEDIATE는 요청이 올 때마다, AT_CLOSE는 접수 창이 닫힌 뒤 묶음으로. */
public enum AllocationTiming { IMMEDIATE, AT_CLOSE }
