package cloud.lazycat.nextlib.example;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;
import io.github.anilbeesetti.nextlib.media3ext.ffextractor.NextExtractorsFactory;

@UnstableApi
public final class PlayerActivity extends Activity {
    private CheckBox preferSoftware;
    private TextView status;
    private PlayerView playerView;
    private ExoPlayer player;
    private Uri mediaUri;
    private SmbServers.Server server;
    private String videoDecoder = "—", audioDecoder = "—", state = "准备播放";
    private long position;
    private boolean playWhenReady = true;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        mediaUri = getIntent().getData();
        server = mediaUri == null ? null : SmbServers.get(mediaUri.getAuthority());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff16181c);
        root.setPadding(dp(8),0,dp(8),0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(8) + insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    dp(8) + insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout actions = new LinearLayout(this);
        Button back = new Button(this); back.setText("返回目录"); back.setOnClickListener(v -> finish()); actions.addView(back);
        Button retry = new Button(this); retry.setText("重试");
        retry.setOnClickListener(v -> { releasePlayer(); playWhenReady = true; createPlayer(); }); actions.addView(retry);
        preferSoftware = new CheckBox(this); preferSoftware.setText("FFmpeg 优先");
        preferSoftware.setTextColor(0xffeeeeee);
        if (saved != null) {
            position = saved.getLong("position"); playWhenReady = saved.getBoolean("play", true);
            preferSoftware.setChecked(saved.getBoolean("software"));
        }
        preferSoftware.setOnCheckedChangeListener((button, checked) -> { releasePlayer(); createPlayer(); });
        actions.addView(preferSoftware); root.addView(actions);
        TextView title = new TextView(this); title.setTextColor(0xffeeeeee); title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setText(mediaUri == null ? "" : mediaUri.getLastPathSegment()); root.addView(title);
        status = new TextView(this); status.setTextColor(0xffb0bec5); status.setTextSize(12); status.setMaxLines(3);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(56)));
        playerView = new PlayerView(this); playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        root.addView(playerView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (server == null) state = "连接已失效，请返回目录重新连接 SMB";
        updateStatus();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putLong("position", player == null ? position : player.getCurrentPosition());
        out.putBoolean("play", player == null ? playWhenReady : player.getPlayWhenReady());
        out.putBoolean("software", preferSoftware.isChecked());
        super.onSaveInstanceState(out);
    }
    private void createPlayer() {
        if (mediaUri == null || server == null || player != null) return;
        videoDecoder = audioDecoder = "—";
        androidx.media3.datasource.DataSource.Factory data = () -> new SmbDataSource(server);
        NextRenderersFactory renderers = new NextRenderersFactory(this);
        renderers.setExtensionRendererMode(preferSoftware.isChecked()
                ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        renderers.setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data, new NextExtractorsFactory()))
                .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int playbackState) {
                switch (playbackState) {
                    case Player.STATE_BUFFERING: state = "加载中…"; break;
                    case Player.STATE_READY: state = "就绪"; break;
                    case Player.STATE_ENDED: state = "播放结束"; break;
                    default: state = "等待播放";
                }
                updateStatus();
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                playerView.setKeepScreenOn(playing);
            }
            @Override public void onPlayerError(PlaybackException error) {
                // Exception messages can contain authenticated URLs. Display only the error code.
                state = "播放失败：" + error.getErrorCodeName() + "；点击重试可重新连接";
                updateStatus();
            }
        });
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime time, String name, long timestampMs, long durationMs) {
                videoDecoder = name; updateStatus();
            }
            @Override public void onAudioDecoderInitialized(EventTime time, String name, long timestampMs, long durationMs) {
                audioDecoder = name; updateStatus();
            }
        });
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(mediaUri), position);
        player.setPlayWhenReady(playWhenReady);
        player.prepare();
        playerView.requestFocus();
    }

    private void releasePlayer() {
        if (player == null) return;
        position = player.getCurrentPosition();
        playWhenReady = player.getPlayWhenReady();
        playerView.setPlayer(null);
        player.release();
        player = null;
        playerView.setKeepScreenOn(false);
    }

    @Override protected void onStart() { super.onStart(); createPlayer(); }
    @Override protected void onStop() { releasePlayer(); super.onStop(); }
    @Override protected void onDestroy() { releasePlayer(); super.onDestroy(); }
    private void updateStatus() {
        if (status != null) status.setText(state + "\n视频：" + videoDecoder + "\n音频：" + audioDecoder);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
