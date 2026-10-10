package com.example.server.service.external;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ExternalVideoImportServiceTest {

    private final ExternalVideoImportService service = new ExternalVideoImportService(
            List.of(),
            mock(ExternalVideoStore.class)
    );

    @Test
    void rejectsHostsOutsideTheExplicitAllowlist() {
        assertThatThrownBy(() -> service.importVideo("http://127.0.0.1:9090/private", 7L))
                .isInstanceOf(ExternalVideoException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_PLATFORM");
    }
}
