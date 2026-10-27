package com.velora.api.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.api.store.service.StoreProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class StoreProfileMissingFieldsIntegrationTest {

    @Autowired private StoreProfileService storeProfileService;

    @Test
    @DisplayName("missingFields never asks for the tax number or the commercial register")
    void taxFieldsAreNotReportedMissing() {
        assertThat(storeProfileService.get().missingFields())
                .doesNotContain("taxNumber", "commercialRegister");
    }
}
