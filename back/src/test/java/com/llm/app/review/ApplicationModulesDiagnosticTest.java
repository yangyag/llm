package com.llm.app.review;

import com.llm.app.LlmApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModulesDiagnosticTest {

    @Test
    void applicationModulesShouldVerify() {
        ApplicationModules.of(LlmApplication.class).verify();
    }
}
