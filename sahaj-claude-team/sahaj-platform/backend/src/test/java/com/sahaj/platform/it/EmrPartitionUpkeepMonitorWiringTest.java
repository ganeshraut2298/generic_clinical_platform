package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.opd.infrastructure.partition.EmrPartitionUpkeepMonitor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * DEC-049 item 3, S-79 (wiring, no database): Spring must be able to create {@link EmrPartitionUpkeepMonitor} as a bean
 * from the real class, with only a {@code JdbcClient} available. The class has two constructors (public with the
 * {@code JdbcClient}, package-private with a {@code Supplier} for the unit tests); Spring picks a constructor by itself
 * only when there is exactly one, or when one is marked {@code @Autowired}, and otherwise asks for a no-argument one. The
 * unit tests of the monitor and the three {@code SpringBootTest}s that switch it off cannot see this; every full context
 * (the application, and every {@code @OnTestDatabase} test) can. This test is the quick, database-free check of that wiring.
 */
class EmrPartitionUpkeepMonitorWiringTest {

    @Test
    void springCanCreateTheMonitorBeanFromItsJdbcClientConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcClient.class, () -> Mockito.mock(JdbcClient.class));
            context.register(EmrPartitionUpkeepMonitor.class);
            context.refresh();
            assertThat(context.getBean(EmrPartitionUpkeepMonitor.class)).isNotNull();
        }
    }
}
