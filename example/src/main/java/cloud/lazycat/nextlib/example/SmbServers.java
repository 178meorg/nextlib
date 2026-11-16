package cloud.lazycat.nextlib.example;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/** Profile metadata and encrypted passwords persist; authenticated transports stay in this process. */
final class SmbServers {
    private static final Map<String, Server> SERVERS = new LinkedHashMap<>();
    private static boolean loaded;
    static synchronized List<Server> all(Context context) {
        if (!loaded) {
            loaded = true;
            try {
                JSONArray array = new JSONArray(context.getSharedPreferences("smb", 0).getString("servers", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    String password = PasswordStore.load(context,obj.getString("id"));
                    Server server = new Server(obj.getString("id"), obj.getString("host"),
                            obj.optString("root"), obj.optString("user"), obj.optString("domain"), password == null ? "" : password);
                    server.hasCredentials = server.user.isEmpty() || password != null;
                    SERVERS.put(server.id, server);
                }
            } catch (Exception ignored) { /* Corrupt profile metadata must not prevent opening the app. */ }
        }
        return new ArrayList<>(SERVERS.values());
    }
    static synchronized Server get(String id) { return SERVERS.get(id); }
    static synchronized void put(Context context, Server server) throws Exception {
        PasswordStore.save(context,server.id,server.password);
        SERVERS.put(server.id, server);
        save(context);
    }
    static synchronized void remove(Context context, String id) { SERVERS.remove(id); PasswordStore.remove(context,id); save(context); }
    private static void save(Context context) {
        JSONArray array = new JSONArray();
        for (Server server : SERVERS.values()) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", server.id).put("host", server.host).put("root", server.root)
                        .put("user", server.user).put("domain", server.domain);
                array.put(obj);
            } catch (Exception ignored) { }
        }
        context.getSharedPreferences("smb", 0).edit().putString("servers", array.toString()).apply();
    }

    static final class Server {
        final String id, host, root, user, domain;
        final String password;
        boolean hasCredentials = true;
        private CIFSContext transport;
        private CIFSContext authenticated;
        Server(String id, String host, String root, String user, String domain, String password) {
            this.id = id == null ? UUID.randomUUID().toString() : id;
            String value = host.trim();
            if (!value.startsWith("smb://")) value = "smb://" + value;
            Uri uri = Uri.parse(value);
            if (!"smb".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException("请输入服务器 IP 或主机名，可带端口");
            this.host = uri.getAuthority();
            String prefix = uri.getPath() == null ? "" : uri.getPath();
            this.root = normalize(prefix + "/" + root.trim());
            this.user = user.trim(); this.domain = domain.trim(); this.password = password;
        }
        synchronized CIFSContext context() throws IOException {
            if (authenticated == null) {
                Properties properties = new Properties();
                properties.setProperty("jcifs.smb.client.minVersion", "SMB202");
                properties.setProperty("jcifs.smb.client.maxVersion", "SMB311");
                properties.setProperty("jcifs.smb.client.connTimeout", "5000");
                properties.setProperty("jcifs.smb.client.responseTimeout", "10000");
                properties.setProperty("jcifs.smb.client.soTimeout", "15000");
                transport = new BaseContext(new PropertyConfiguration(properties));
                authenticated = user.isEmpty() ? transport.withGuestCrendentials()
                        : transport.withCredentials(new NtlmPasswordAuthenticator(domain, user, password));
            }
            return authenticated;
        }
        List<String> shares() throws IOException {
            // jcifs-ng's listFiles(serverRoot) drops custom ports when constructing its RPC pipe.
            // Preserve the authority explicitly for share enumeration as well as file reads.
            try (jcifs.dcerpc.DcerpcHandle handle = jcifs.dcerpc.DcerpcHandle.getHandle(
                    "ncacn_np:" + host + "[\\PIPE\\srvsvc]", context())) {
                jcifs.dcerpc.msrpc.MsrpcShareEnum request = new jcifs.dcerpc.msrpc.MsrpcShareEnum(
                        Uri.parse("smb://" + host).getHost());
                handle.sendrecv(request);
                if (request.retval != 0) throw new jcifs.smb.SmbException(request.retval, true);
                List<String> names = new ArrayList<>();
                for (jcifs.smb.FileEntry entry : request.getEntries()) {
                    if (entry.getType() == jcifs.SmbConstants.TYPE_SHARE && !entry.getName().equalsIgnoreCase("IPC$"))
                        names.add(entry.getName());
                }
                return names;
            }
        }
        SmbFile file(String path, boolean directory) throws IOException {
            SmbFile current = new SmbFile("smb://" + host + "/", context());
            String normalized = normalize(path);
            if (normalized.isEmpty()) return current;
            String[] parts = normalized.split("/");
            try {
                for (int i = 0; i < parts.length; i++) {
                    SmbFile next = new SmbFile(current, parts[i] + (i < parts.length - 1 || directory ? "/" : ""));
                    current.close();
                    current = next;
                }
                return current;
            } catch (IOException | RuntimeException error) { current.close(); throw error; }
        }
        synchronized void close() {
            try { if (transport != null) transport.close(); } catch (IOException ignored) { }
            transport = authenticated = null;
        }
        Uri mediaUri(String path) {
            return new Uri.Builder().scheme("smb").authority(id).path("/" + normalize(path)).build();
        }
        static String normalize(String path) {
            StringBuilder result = new StringBuilder();
            for (String part : path.split("/")) {
                if (part.isEmpty()) continue;
                if (part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0)
                    throw new IllegalArgumentException("共享路径不能包含 .、.. 或反斜杠");
                if (result.length() > 0) result.append('/');
                result.append(part);
            }
            return result.toString();
        }
    }
}
