package cloud.lazycat.nextlib.example;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DataSourceException;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import jcifs.smb.SmbFile;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
@androidx.media3.common.util.UnstableApi
public class SmbDataSourceTest {
    private SmbServers.Server server(String password) {
        String port = System.getProperty("nextlib.smb.port"); assumeNotNull(port);
        return new SmbServers.Server("test","127.0.0.1:"+port,"media","nextlib","",password);
    }
    @Test public void rejectsCredentialsInHostAndParentPaths() {
        for (String host : new String[]{"smb://user:password@server", "smb://", "host:70000"}) {
            try { new SmbServers.Server(null,host,"","","",""); fail(host); }
            catch (IllegalArgumentException expected) { }
        }
        try { SmbServers.Server.normalize("media/../private"); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void authenticatesAndBrowsesSharesAndUnicodeDirectory() throws Exception {
        SmbServers.Server server=server("nextlib-test");
        try {
            assertTrue(server.shares().contains("media"));
            try (SmbFile folder=server.file("media/目录 #100%",true)) {
                SmbFile[] files=folder.listFiles(); boolean found=false;
                for(SmbFile file:files) { found |= file.getName().equals("视频 #100%.mp4"); file.close(); }
                assertTrue(found);
            }
        } finally { server.close(); }
    }
    @Test public void rangesReopenAndEofPreserveExactBytes() throws Exception {
        SmbServers.Server server=server("nextlib-test");
        SmbDataSource source=new SmbDataSource(server);
        String path="media/目录 #100%/视频 #100%.mp4";
        try {
            for(int position:new int[]{0,130001,43,262150}) {
                DataSpec spec=new DataSpec.Builder().setUri(server.mediaUri(path)).setPosition(position).setLength(7).build();
                assertEquals(7,source.open(spec));
                byte[] buffer=new byte[7]; int count=0;
                while(count<7) count+=source.read(buffer,count,7-count);
                for(int i=0;i<7;i++) assertEquals((byte)((position+i)%251),buffer[i]);
                assertEquals(C.RESULT_END_OF_INPUT,source.read(buffer,0,7)); source.close();
            }
            assertEquals(0,source.open(new DataSpec.Builder().setUri(server.mediaUri(path)).setPosition(262161).build()));
            assertEquals(-1,source.read(new byte[1],0,1)); source.close();
            try { source.open(new DataSpec.Builder().setUri(server.mediaUri(path)).setPosition(262162).build()); fail(); }
            catch(DataSourceException expected) { }
        } finally { source.close(); server.close(); }
    }
    @Test(timeout=15000) public void tinySniffReadsAreBatchedAndSeekDiscardsReadAhead() throws Exception {
        SmbServers.Server server=server("nextlib-test");
        SmbDataSource source=new SmbDataSource(server);
        int[] transfers={0};
        source.addTransferListener(new androidx.media3.datasource.TransferListener() {
            public void onTransferInitializing(androidx.media3.datasource.DataSource s,DataSpec d,boolean network) {}
            public void onTransferStart(androidx.media3.datasource.DataSource s,DataSpec d,boolean network) {}
            public void onBytesTransferred(androidx.media3.datasource.DataSource s,DataSpec d,boolean network,int bytes) { transfers[0]++; }
            public void onTransferEnd(androidx.media3.datasource.DataSource s,DataSpec d,boolean network) {}
        });
        String path="media/目录 #100%/视频 #100%.mp4";
        try {
            source.open(new DataSpec(server.mediaUri(path)));
            byte[] one=new byte[1];
            for(int i=0;i<32768;i++) {
                assertEquals(1,source.read(one,0,1));
                assertEquals((byte)(i%251),one[0]);
            }
            assertEquals("32K small peeks must share one read-ahead refill",1,transfers[0]);
            source.close();
            source.open(new DataSpec.Builder().setUri(server.mediaUri(path)).setPosition(123456).build());
            assertEquals(1,source.read(one,0,1));
            assertEquals((byte)(123456%251),one[0]);
        } finally { source.close(); server.close(); }
    }
    @Test public void badPasswordFailsThenNewSessionCanRetry() throws Exception {
        SmbServers.Server bad=server("incorrect");
        SmbDataSource source=new SmbDataSource(bad);
        try {
            try { source.open(new DataSpec(bad.mediaUri("media/目录 #100%/视频 #100%.mp4"))); fail(); }
            catch(IOException expected) { }
        } finally { source.close(); bad.close(); }
        rangesReopenAndEofPreserveExactBytes();
    }
}
