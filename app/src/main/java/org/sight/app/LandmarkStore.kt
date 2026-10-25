package org.sight.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.sight.core.MemorySnapshot
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedLandmark(val name: String, val savedAtEpochMs: Long, val source: String,
                         val description: String, val observedText: String?)

/** Explicit saves only. No media/descriptors, backup disabled, AES-GCM key stays in Android Keystore. */
class LandmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences("landmarks", Context.MODE_PRIVATE)
    private val alias = "sight.landmarks.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): List<SavedLandmark> {
        val encoded = prefs.getString("data", null) ?: return emptyList()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return (0 until array.length()).map { index -> array.getJSONObject(index).let {
            SavedLandmark(it.getString("name"), it.getLong("time"), it.getString("source"),
                it.getString("description"), it.optString("text").takeIf(String::isNotBlank))
        } }.filter { System.currentTimeMillis() - it.savedAtEpochMs <= 30L*24*60*60*1000 }
    }
    fun save(memory: MemorySnapshot) {
        val previous = load().filterNot { it.name.equals(memory.name, true) }
        val landmark = SavedLandmark(memory.name, System.currentTimeMillis(), memory.evidence.sourceId,
            memory.entities.joinToString { "${it.category} ${it.direction.phrase} in the saved image" }, memory.text)
        write((previous + landmark).takeLast(20))
    }
    private fun write(items: List<SavedLandmark>) {
        val array = JSONArray(items.map { JSONObject().put("name", it.name).put("time", it.savedAtEpochMs)
            .put("source", it.source).put("description", it.description).put("text", it.observedText ?: "") })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }
    fun delete(name: String) = write(load().filterNot { it.name == name })
    fun clear() { prefs.edit().clear().apply() }
}
