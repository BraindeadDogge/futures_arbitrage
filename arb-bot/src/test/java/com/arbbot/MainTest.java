package com.arbbot;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void mainStartsAndShutsDownCleanly() throws Exception {
        assertDoesNotThrow(() -> {
            var config = new com.arbbot.config.AppConfig(
                com.typesafe.config.ConfigFactory.load("application-test"));
            assertNotNull(config.scannerConfig());
            assertNotNull(config.healthConfig());
        });
    }
}
