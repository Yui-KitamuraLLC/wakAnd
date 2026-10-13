package pro.eng.yui.android.wakAnd;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** One atomic, encrypted document in noBackupFilesDir, including the revocation queue. */
final class OAuthStore {
    static final Object LOCK = new Object();
    private static final String ALIAS = "wakand_official_oauth_v1";
    private final AtomicFile file;
    OAuthStore(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "official_oauth_v1")); }
    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            if (!create) throw new IllegalStateException("reauth_required");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    JSONObject read() throws Exception {
        if (!file.getBaseFile().exists()) return new JSONObject();
        byte[] bytes = file.readFully();
        if (bytes.length < 29) throw new IllegalStateException("reauth_required");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8));
    }
    void write(JSONObject document) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(document.toString().getBytes(StandardCharsets.UTF_8));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(cipher.getIV()); stream.write(encrypted); file.finishWrite(stream);
        } catch (Exception error) { if (stream != null) file.failWrite(stream); throw new IllegalStateException("storage_unavailable"); }
    }
}
