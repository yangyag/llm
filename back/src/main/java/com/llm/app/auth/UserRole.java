package com.llm.app.auth;

/** 사용자 레벨. ADMIN(관리자)은 사용자 관리 API 사용 가능, USER(일반사용자)는 불가. */
public enum UserRole {
    ADMIN,
    USER;

    public static UserRole from(String value) {
        return UserRole.valueOf(value.trim().toUpperCase());
    }
}
