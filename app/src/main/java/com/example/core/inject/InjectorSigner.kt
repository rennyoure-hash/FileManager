package com.example.core.inject

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signs an APK with the bundled key so it can be installed.
 *
 * The injected APK is signed with our own key, which means it cannot replace an
 * existing app in place - the original has to be uninstalled first (and with it the
 * app's data). This is the same trade-off every APK re-signing tool has.
 */
object InjectorSigner {

    private const val KEYSTORE_ASSET = "mt_inject_keystore.p12"
    private const val KEYSTORE_PASSWORD = "mtinject"
    private const val KEY_ALIAS = "mtinject"

    data class KeyMaterial(val key: PrivateKey, val cert: X509Certificate)

    /**
     * Loads the bundled signing key. Falls back to generating an ephemeral one when the
     * asset is missing (keeps the feature usable instead of failing hard).
     */
    fun loadKey(context: Context): KeyMaterial {
        return try {
            context.assets.open(KEYSTORE_ASSET).use { loadFromStream(it) }
        } catch (_: Throwable) {
            generateEphemeral()
        }
    }

    private fun loadFromStream(input: InputStream): KeyMaterial {
        val ks = KeyStore.getInstance("PKCS12")
        input.use { ks.load(it, KEYSTORE_PASSWORD.toCharArray()) }
        val key = ks.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        return KeyMaterial(key, cert)
    }

    private fun generateEphemeral(): KeyMaterial {
        // Fallback so the feature still works when the bundled asset is missing/corrupt.
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val alias = "mt_inject_ephemeral"
        if (!ks.containsAlias(alias)) {
            val gen = java.security.KeyPairGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )
            val spec = java.security.spec.ECGenParameterSpec("secp256r1")
            gen.initialize(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    alias,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or
                        android.security.keystore.KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(spec)
                    .build()
            )
            gen.generateKeyPair()
        }
        val key = ks.getKey(alias, null) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        return KeyMaterial(key, cert)
    }

    /**
     * Signs [inputApk] into [outputApk].
     *
     * @param minSdkVersion lowest SDK the result must support (drives which signature
     *        schemes are used: v1 + v2 + v3).
     */
    fun sign(inputApk: File, outputApk: File, keyMaterial: KeyMaterial, minSdkVersion: Int) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "MT Injector",
            keyMaterial.key,
            listOf(keyMaterial.cert)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setMinSdkVersion(minSdkVersion)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
