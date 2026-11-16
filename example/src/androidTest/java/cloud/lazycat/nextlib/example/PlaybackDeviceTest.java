package cloud.lazycat.nextlib.example;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.util.UnstableApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@UnstableApi
public class PlaybackDeviceTest {
    @Test public void savedPasswordRoundTripsAndRejectsTampering() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id = "test-password-store";
        try {
            PasswordStore.save(context,id,"test-only-中文-password");
            assertEquals("test-only-中文-password",PasswordStore.load(context,id));
            String encrypted = context.getSharedPreferences("smb-passwords",0).getString(id,"");
            assertFalse(encrypted.contains("test-only"));
            context.getSharedPreferences("smb-passwords",0).edit().putString(id+"-other",encrypted).commit();
            assertNull(PasswordStore.load(context,id+"-other"));
            PasswordStore.save(context,id,"");
            assertEquals("",PasswordStore.load(context,id));
            PasswordStore.remove(context,id);
            assertNull(PasswordStore.load(context,id));
        } finally { PasswordStore.remove(context,id); PasswordStore.remove(context,id+"-other"); }
    }

    @Test public void realMediaAacUsesSystemDecoderAndSurvivesSeek() throws Exception {
        play("aac.rm",false);
    }
    @Test public void realVideoRendersAndSurvivesSeek() throws Exception {
        play("rv40.rm",true);
    }
    private void play(String name,boolean video) throws Exception {
        assumeTrue("Pass -e smbPlayback true with adb reverse tcp:1446 tcp:1446",
                InstrumentationRegistry.getArguments().getString("smbPlayback", "false").equals("true"));
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        String id = "test-playback-"+name;
        SmbServers.all(context);
        SmbServers.Server server = new SmbServers.Server(id,"127.0.0.1:1446","media","nextlib","","nextlib-test");
        SmbServers.put(context,server);
        PlayerActivity activity = null;
        try {
            activity = (PlayerActivity)instrumentation.startActivitySync(new Intent(context,PlayerActivity.class)
                    .setData(server.mediaUri("media/"+name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Field field=PlayerActivity.class.getDeclaredField("player"); field.setAccessible(true);
            ExoPlayer player=(ExoPlayer)field.get(activity);
            awaitPlayback(player,video,1000);
            if (!video) {
                Field decoder=PlayerActivity.class.getDeclaredField("audioDecoder"); decoder.setAccessible(true);
                assertFalse("AAC must use the system decoder in this regression test",((String)decoder.get(activity)).startsWith("ffmpeg"));
            }
            instrumentation.runOnMainSync(() -> player.seekTo(5000));
            awaitPlayback(player,video,5500);
        } finally {
            if (activity != null) {
                PlayerActivity closing=activity; instrumentation.runOnMainSync(closing::finish);
                instrumentation.waitForIdleSync();
            }
            SmbServers.remove(context,id); server.close();
        }
    }
    private void awaitPlayback(ExoPlayer player,boolean video,long position) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+25000;
        while(SystemClock.elapsedRealtime()<deadline) {
            AtomicBoolean ready=new AtomicBoolean(); AtomicReference<Throwable> error=new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                error.set(player.getPlayerError());
                androidx.media3.exoplayer.DecoderCounters counters=video ? player.getVideoDecoderCounters() : player.getAudioDecoderCounters();
                ready.set(player.getCurrentPosition()>=position && counters!=null && counters.renderedOutputBufferCount>0);
            });
            if(error.get()!=null) throw new AssertionError("Playback failed",error.get());
            if(ready.get()) return;
            SystemClock.sleep(100);
        }
        fail("Playback did not render and advance to "+position+" ms");
    }
}
