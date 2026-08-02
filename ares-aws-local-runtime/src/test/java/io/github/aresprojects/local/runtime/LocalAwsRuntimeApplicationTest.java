package io.github.aresprojects.local.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.aresprojects.local.runtime.trigger.TriggerEngine;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LocalAwsRuntimeApplicationTest {
    @Test
    void startsServerBeforeTriggersAndClosesThemInReverseOrder() {
        LocalAwsServer server = mock(LocalAwsServer.class);
        TriggerEngine engine = mock(TriggerEngine.class);
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 4566);
        when(server.start()).thenReturn(address);
        LocalAwsRuntimeApplication application = new LocalAwsRuntimeApplication(server, engine);

        assertEquals(address, application.start());
        assertThrows(IllegalStateException.class, application::start);
        application.close();
        application.close();

        InOrder order = inOrder(server, engine);
        order.verify(server).start();
        order.verify(engine).start();
        order.verify(engine).close();
        order.verify(server).close();
    }

    @Test
    void closesResourcesWhenTriggerStartupFails() {
        LocalAwsServer server = mock(LocalAwsServer.class);
        TriggerEngine engine = mock(TriggerEngine.class);
        when(server.start()).thenReturn(new InetSocketAddress("127.0.0.1", 4566));
        doThrow(new IllegalStateException("trigger startup failed"))
                .when(engine)
                .start();
        LocalAwsRuntimeApplication application = new LocalAwsRuntimeApplication(server, engine);

        assertThrows(IllegalStateException.class, application::start);

        verify(engine).close();
        verify(server).close();
    }

    @Test
    void closesBeforeStart() {
        LocalAwsServer server = mock(LocalAwsServer.class);
        TriggerEngine engine = mock(TriggerEngine.class);
        LocalAwsRuntimeApplication application = new LocalAwsRuntimeApplication(server, engine);

        application.close();

        verify(engine).close();
        verify(server).close();
    }
}
