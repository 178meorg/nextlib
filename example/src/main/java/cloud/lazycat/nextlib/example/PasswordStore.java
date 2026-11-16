package cloud.lazycat.nextlib.example;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Calendar;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;
import org.json.JSONObject;

/** API 21-compatible envelope encryption: AES-GCM record keys wrapped by Android Keystore RSA. */
final class PasswordStore {
    private static final String ALIAS = "nextlib.smb.passwords.v1";
    private static final String PREFS = "smb-passwords";
    private static final SecureRandom RANDOM = new SecureRandom();
    private PasswordStore() {}

    private static KeyStore keyStore(Context context) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance(); end.add(Calendar.YEAR, 30);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyPairGeneratorSpec.Builder(context.getApplicationContext())
                    .setAlias(ALIAS).setKeySize(2048)
                    .setSubject(new X500Principal("CN=Nextlib SMB Passwords"))
                    .setSerialNumber(BigInteger.ONE).setStartDate(start.getTime()).setEndDate(end.getTime()).build());
            generator.generateKeyPair();
            store.load(null);
        }
        return store;
    }
    static synchronized void save(Context context, String id, String password) throws Exception {
        KeyStore store = keyStore(context);
        byte[] key = new byte[32]; RANDOM.nextBytes(key);
        byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128,iv));
        aes.updateAAD(id.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = aes.doFinal(password.getBytes(StandardCharsets.UTF_8));
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, store.getCertificate(ALIAS).getPublicKey());
        byte[] wrapped = rsa.doFinal(key);
        java.util.Arrays.fill(key,(byte)0);
        JSONObject record = new JSONObject().put("v",1).put("key",encode(wrapped))
                .put("iv",encode(iv)).put("data",encode(encrypted));
        if (!context.getSharedPreferences(PREFS,0).edit().putString(id,record.toString()).commit())
            throw new java.io.IOException("Could not persist encrypted password");
    }
    /** Null means no saved password or an invalidated key; never falls back to plaintext. */
    static synchronized String load(Context context, String id) {
        String saved = context.getSharedPreferences(PREFS,0).getString(id,null);
        if (saved == null) return null;
        try {
            JSONObject record = new JSONObject(saved);
            if (record.getInt("v") != 1) return null;
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            if (!store.containsAlias(ALIAS)) return null;
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.DECRYPT_MODE,(PrivateKey)store.getKey(ALIAS,null));
            byte[] key = rsa.doFinal(decode(record.getString("key")));
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,decode(record.getString("iv"))));
            java.util.Arrays.fill(key,(byte)0);
            aes.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            return new String(aes.doFinal(decode(record.getString("data"))),StandardCharsets.UTF_8);
        } catch (Exception invalid) { return null; }
    }
    static synchronized void remove(Context context,String id) {
        context.getSharedPreferences(PREFS,0).edit().remove(id).apply();
    }
    private static String encode(byte[] data) { return Base64.encodeToString(data,Base64.NO_WRAP); }
    private static byte[] decode(String data) { return Base64.decode(data,Base64.NO_WRAP); }
}
