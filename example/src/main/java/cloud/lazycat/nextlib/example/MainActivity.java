package cloud.lazycat.nextlib.example;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;

/** SMB profiles -> shared folders -> video. All SMB operations run off the UI thread. */
@androidx.media3.common.util.UnstableApi
public final class MainActivity extends Activity {
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private TextView location, message;
    private ListView list;
    private Button up, refresh;
    private SmbServers.Server server;
    private String path = "";
    private int generation;
    private boolean destroyed;
    private List<SmbServers.Server> profiles = new ArrayList<>();
    private List<Entry> entries = new ArrayList<>();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff16181c);
        root.setPadding(dp(12),dp(8),dp(12),dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(12) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(12) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout actions = new LinearLayout(this);
        Button add = new Button(this); add.setText("添加 SMB"); add.setOnClickListener(v -> editServer(null)); actions.addView(add);
        up = new Button(this); up.setText("上一级"); up.setOnClickListener(v -> goUp()); actions.addView(up);
        refresh = new Button(this); refresh.setText("刷新");
        refresh.setOnClickListener(v -> { if (server == null) showProfiles(); else browse(path); }); actions.addView(refresh);
        root.addView(actions);
        location = new TextView(this); location.setTextColor(0xffeeeeee); location.setTextSize(16);
        location.setMaxLines(2); location.setTextIsSelectable(true); root.addView(location);
        message = new TextView(this); message.setTextColor(0xffb0bec5); message.setTextSize(13);
        message.setPadding(0,dp(8),0,dp(8)); root.addView(message);
        list = new ListView(this); root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,view,index,id) -> {
            if (server == null) {
                if (index >= profiles.size()) return;
                SmbServers.Server selected = profiles.get(index);
                if (!selected.hasCredentials) editServer(selected); else connect(selected);
            } else {
                if (index >= entries.size()) return;
                Entry entry = entries.get(index);
                String child = path.isEmpty() ? entry.name : path + "/" + entry.name;
                if (entry.directory) browse(child);
                else startActivity(new Intent(this, PlayerActivity.class).setData(server.mediaUri(child)));
            }
        });
        list.setOnItemLongClickListener((parent,view,index,id) -> {
            if (server != null || index >= profiles.size()) return false;
            SmbServers.Server selected = profiles.get(index);
            new AlertDialog.Builder(this).setTitle(selected.host)
                    .setItems(new String[]{"编辑连接", "删除"}, (dialog,which) -> {
                        if (which == 0) editServer(selected);
                        else { SmbServers.remove(this,selected.id); io.execute(selected::close); showProfiles(); }
                    }).show();
            return true;
        });
        setContentView(root);
        showProfiles();
        if (saved != null) {
            SmbServers.Server previous = SmbServers.get(saved.getString("server"));
            if (previous != null && previous.hasCredentials) {
                server = previous; browse(saved.getString("path",previous.root));
            }
        }
    }

    private void showProfiles() {
        generation++; server = null; path = ""; up.setEnabled(false); refresh.setEnabled(true);
        location.setText("SMB 服务器");
        profiles = SmbServers.all(this);
        List<String> rows = new ArrayList<>();
        for (SmbServers.Server profile : profiles)
            rows.add(profile.host + (profile.root.isEmpty() ? "" : "/" + profile.root)
                    + "\n" + (profile.user.isEmpty() ? "访客" : profile.user));
        list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows));
        message.setText(profiles.isEmpty() ? "添加服务器后，即可浏览共享目录并播放视频。" : "点击连接；长按可编辑或删除。");
    }
    private void connect(SmbServers.Server selected) { server = selected; browse(selected.root); }
    private void browse(String target) {
        path = target;
        final SmbServers.Server current = server;
        final int request = ++generation;
        up.setEnabled(true); refresh.setEnabled(false);
        location.setText(current.host + "/" + path);
        message.setText("正在读取目录…");
        entries = new ArrayList<>();
        list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,new ArrayList<>()));
        io.execute(() -> {
            try {
                List<Entry> found = new ArrayList<>();
                if (target.isEmpty()) {
                    for (String name : current.shares()) found.add(new Entry(name,true));
                } else try (SmbFile directory = current.file(target,true)) {
                    SmbFile[] children = directory.listFiles();
                    try {
                        for (SmbFile file : children) {
                            String name = file.getName();
                            while (name.endsWith("/")) name = name.substring(0,name.length()-1);
                            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.equalsIgnoreCase("IPC$")) continue;
                            boolean folder = file.isDirectory();
                            if (folder || isVideo(name)) found.add(new Entry(name,folder));
                        }
                    } finally { for (SmbFile child : children) child.close(); }
                }
                Collections.sort(found,(a,b) -> a.directory == b.directory
                        ? a.name.compareToIgnoreCase(b.name) : a.directory ? -1 : 1);
                runOnUiThread(() -> {
                    if (destroyed || request != generation) return;
                    entries = found;
                    List<String> rows = new ArrayList<>();
                    for (Entry entry : found) rows.add((entry.directory ? "目录  ·  " : "视频  ·  ") + entry.name);
                    list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows));
                    message.setText(found.isEmpty() ? "此目录没有子目录或视频" : "点击目录浏览，点击视频播放");
                    refresh.setEnabled(true);
                });
            } catch (Exception error) {
                final String text = error instanceof SmbAuthException
                        ? "认证失败或无访问权限，请返回服务器列表编辑账号密码。"
                        : error instanceof jcifs.smb.SmbUnsupportedOperationException
                        ? "服务器要求的 SMB 功能暂不支持（例如强制 SMB3 加密）。"
                        : "无法读取目录，请检查服务器地址、共享名及网络，点击刷新重试。";
                runOnUiThread(() -> {
                    if (destroyed || request != generation) return;
                    message.setText(text); refresh.setEnabled(true);
                });
            }
        });
    }
    private void goUp() {
        if (server == null) return;
        if (path.equals(server.root) || path.isEmpty()) { showProfiles(); return; }
        int slash = path.lastIndexOf('/'); browse(slash < 0 ? "" : path.substring(0,slash));
    }
    @Override public void onBackPressed() { if (server != null) goUp(); else super.onBackPressed(); }

    private void editServer(SmbServers.Server previous) {
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20),dp(8),dp(20),0);
        EditText host = field(fields,"服务器 IP / 主机名，可带端口",previous == null ? "" : previous.host,false);
        EditText share = field(fields,"共享名 / 初始目录（可选）",previous == null ? "" : previous.root,false);
        EditText user = field(fields,"用户名（留空使用访客）",previous == null ? "" : previous.user,false);
        EditText password = field(fields,"密码（加密保存）",previous == null ? "" : previous.password,true);
        EditText domain = field(fields,"域（可选）",previous == null ? "" : previous.domain,false);
        TextView error = new TextView(this); error.setTextColor(0xffffab91); fields.addView(error);
        ScrollView scroll = new ScrollView(this); scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(previous == null ? "添加 SMB" : "编辑 SMB")
                .setView(scroll).setNegativeButton("取消",null).setPositiveButton("连接",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                SmbServers.Server added = new SmbServers.Server(previous == null ? null : previous.id,
                        host.getText().toString(),share.getText().toString(),user.getText().toString(),
                        domain.getText().toString(),password.getText().toString());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                error.setText("正在保存连接…");
                io.execute(() -> {
                    try {
                        SmbServers.put(getApplicationContext(),added);
                        if (previous != null) previous.close();
                        runOnUiThread(() -> {
                            if (destroyed || !dialog.isShowing()) return;
                            dialog.dismiss(); connect(added);
                        });
                    } catch (Exception failure) {
                        runOnUiThread(() -> {
                            if (destroyed || !dialog.isShowing()) return;
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            error.setText("无法安全保存密码，请重试。");
                        });
                    }
                });
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
        }));
        dialog.show();
    }
    private EditText field(LinearLayout root,String hint,String value,boolean secret) {
        EditText input = new EditText(this); input.setHint(hint); input.setText(value); input.setSingleLine(true);
        input.setSaveEnabled(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        root.addView(input,new LinearLayout.LayoutParams(-1,dp(52))); return input;
    }
    private static boolean isVideo(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && "|rm|rmvb|mp4|mkv|avi|ts|m2ts|mts|mov|mpg|mpeg|vob|wmv|flv|m4v|webm|3gp|asf|divx|ogv|".contains("|"+name.substring(dot+1).toLowerCase(Locale.ROOT)+"|");
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (server != null) { out.putString("server",server.id); out.putString("path",path); }
        super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() { destroyed = true; generation++; io.shutdownNow(); super.onDestroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static final class Entry {
        final String name; final boolean directory;
        Entry(String name,boolean directory) { this.name=name; this.directory=directory; }
    }
}
