package cloud.lazycat.nextlib.example;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Run in two separate instrumentation invocations, with force-stop between them. */
@RunWith(AndroidJUnit4.class)
public class PasswordPersistenceDeviceTest {
    @Test public void connectionSurvivesProcessRestart() throws Exception {
        String mode=InstrumentationRegistry.getArguments().getString("passwordPersistence","");
        assumeTrue(mode.equals("save") || mode.equals("restore"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id="test-password-restart";
        SmbServers.all(context);
        if(mode.equals("save")) {
            SmbServers.put(context,new SmbServers.Server(id,"127.0.0.1:1446","media","nextlib","","restart-test-only"));
            // Flush profile metadata as well as the encrypted password before terminating this process.
            assertTrue(context.getSharedPreferences("smb",0).edit().commit());
        } else {
            try {
                SmbServers.Server server=SmbServers.get(id);
                assertNotNull(server);
                assertTrue(server.hasCredentials);
                assertEquals("restart-test-only",server.password);
            } finally { SmbServers.remove(context,id); }
        }
    }
}
