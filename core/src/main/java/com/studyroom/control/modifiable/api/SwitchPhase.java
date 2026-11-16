package com.studyroom.control.modifiable.api;

/** 전환 상태(Feature Toggle의 상태 기계). ACTIVE → SWITCH_REQUESTED → DRAINING → ACTIVE. */
public enum SwitchPhase { ACTIVE, SWITCH_REQUESTED, DRAINING }
