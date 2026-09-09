package se.optiqon.rotationtest

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Synthetic package for the L1 signing-rotation matrix. On every launch it
 *  1. creates (first launch) or reads a Room row and an EncryptedSharedPreferences value,
 *  2. asks the platform which certificate(s) it considers the signer of this package,
 *  3. writes one machine-readable line to logcat (tag ROTTEST) and to files/state.txt.
 * The matrix script compares the marker/secret across installs and reads the effective
 * signer as the platform reports it, which is the only view that matters for rotation.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val line = try { collect() } catch (t: Throwable) { "ROTTEST error=${t.javaClass.simpleName}:${t.message}" }
        Log.i(TAG, line)
        File(filesDir, "state.txt").writeText(line + "\n")
        finish()
    }

    private fun collect(): String {
        val db = Room.databaseBuilder(this, RotationDb::class.java, "rotation.db")
            .allowMainThreadQueries().build()
        val dao = db.markers()
        val created = dao.get() == null
        if (created) dao.insert(Marker(uuid = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis()))
        val marker = dao.get()!!
        db.close()

        val prefs = EncryptedSharedPreferences.create(
            "secure", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        var secret = prefs.getString("secret", null)
        if (secret == null) {
            secret = UUID.randomUUID().toString()
            prefs.edit().putString("secret", secret).commit()
        }

        val info = packageManager.getPackageInfo(packageName, signingFlags())
        val current: List<String>
        val history: List<String>
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo!!
            current = si.apkContentsSigners.map { sha256(it.toByteArray()) }
            history = if (si.hasMultipleSigners()) emptyList()
                      else si.signingCertificateHistory.map { sha256(it.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            current = info.signatures!!.map { sha256(it.toByteArray()) }
            history = emptyList()
        }
        @Suppress("DEPRECATION")
        val vc = info.versionCode
        return "ROTTEST api=${Build.VERSION.SDK_INT} versionCode=$vc created=$created " +
            "marker=${marker.uuid} secretDigest=${sha256(secret.toByteArray()).take(16)} " +
            "signer=${current.joinToString(",")} history=${history.joinToString(",")}"
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    companion object { const val TAG = "ROTTEST" }
}
