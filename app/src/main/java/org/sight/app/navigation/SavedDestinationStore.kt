package org.sight.app.navigation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedDestination(val name: String, val destination: Destination, val savedAtEpochMs: Long)

/** Only an explicit Save command writes coordinates. Separate Keystore key, bounded and deletable. */
internal class SavedDestinationStore(context: Context) {
    private val preferences = context.getSharedPreferences("saved-destinations", Context.MODE_PRIVATE)
    private val alias = "sight.saved-destinations"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): List<SavedDestination> {
        val encoded = preferences.getString("data", null) ?: return emptyList()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size in 29..40_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        require(array.length() <= 20)
        return (0 until array.length()).map { index -> array.getJSONObject(index).let {
            SavedDestination(RouteSpeech.label(it.getString("name")).take(60),
                Destination(it.getString("id"), RouteSpeech.label(it.getString("label")),
                    GeoPoint(it.getDouble("latitude"), it.getDouble("longitude"))), it.getLong("savedAt"))
        } }.filter { System.currentTimeMillis() - it.savedAtEpochMs in 0..30L*24*60*60*1000 }
    }
    fun save(name: String, destination: Destination): List<SavedDestination> {
        val items = (load().filterNot { it.name.equals(name, true) } +
            SavedDestination(RouteSpeech.label(name).take(60), destination, System.currentTimeMillis())).takeLast(20)
        write(items)
        return items
    }
    fun delete(name: String): List<SavedDestination> = load().filterNot { it.name.equals(name, true) }.also(::write)
    fun clear() { check(preferences.edit().clear().commit()) { "Destination deletion could not be written" } }
    private fun write(items: List<SavedDestination>) {
        val array = JSONArray(items.map { saved -> JSONObject().put("name", saved.name).put("savedAt", saved.savedAtEpochMs)
            .put("id", saved.destination.id).put("label", saved.destination.label)
            .put("latitude", saved.destination.point.latitude).put("longitude", saved.destination.point.longitude) })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString("data", encoded).commit()) { "Destination save could not be written" }
    }
}
