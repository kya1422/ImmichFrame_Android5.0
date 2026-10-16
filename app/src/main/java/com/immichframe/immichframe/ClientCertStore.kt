package com.immichframe.immichframe

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.webkit.ClientCertRequest
import androidx.preference.PreferenceManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.KeyManager
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

/** Thrown when an imported client certificate cannot be used. The message is user facing. */
class ClientCertException(message: String) : Exception(message)

/**
 * Stores and uses a TLS client certificate (mTLS) so that ImmichFrame can authenticate itself
 * against Cloudflare Access "Mutual TLS" (or any other mTLS protected reverse proxy).
 *
 * The certificate is imported by the user from the settings screen, either as a PKCS#12 file
 * (.p12/.pfx) or as the PEM files handed out by the Cloudflare dashboard (certificate + private
 * key). Everything is kept in the app private storage:
 *
 *   files/client_cert/client.p12   PKCS#12 holding the private key and the certificate chain
 *   files/client_cert/client.pk8   fallback (raw PKCS#8 key) when no PKCS#12 writer exists
 *   files/client_cert/client.cer   fallback (concatenated DER certificates)
 *
 * Two consumers use the material:
 *   - WebView  -> [proceedWithClientCertificate] answers android.webkit.ClientCertRequest
 *   - OkHttp   -> [keyManagers] is plugged into the SSLContext used by Retrofit
 */
object ClientCertStore {
    private const val TAG = "ClientCertStore"

    const val PREF_ENABLED = "clientCertEnabled"
    private const val PREF_PASSWORD = "clientCertStorePassword"
    private const val PREF_FORMAT = "clientCertStoreFormat"

    private const val KEY_ALIAS = "immichframe-client"
    private const val DIR_NAME = "client_cert"
    private const val P12_NAME = "client.p12"
    private const val RAW_KEY_NAME = "client.pk8"
    private const val RAW_CHAIN_NAME = "client.cer"

    private const val FORMAT_P12 = "p12"
    private const val FORMAT_RAW = "raw"

    /** Private key plus its certificate chain (leaf certificate first). */
    data class Material(val privateKey: PrivateKey, val chain: Array<X509Certificate>)

    // --- Preferences / files -------------------------------------------------

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private fun dir(context: Context) = File(context.filesDir, DIR_NAME)

    private fun p12File(context: Context) = File(dir(context), P12_NAME)

    private fun rawKeyFile(context: Context) = File(dir(context), RAW_KEY_NAME)

    private fun rawChainFile(context: Context) = File(dir(context), RAW_CHAIN_NAME)

    fun hasCertificate(context: Context): Boolean =
        p12File(context).exists() || (rawKeyFile(context).exists() && rawChainFile(context).exists())

    /** True when a certificate is imported *and* the user enabled mTLS. */
    fun isEnabled(context: Context): Boolean =
        hasCertificate(context) && prefs(context).getBoolean(PREF_ENABLED, false)

    /**
     * Opaque value that changes whenever the stored certificate changes. Used by [Helpers] to
     * invalidate its cached SSLContext after a new certificate has been imported.
     */
    fun stamp(context: Context): String? {
        if (!isEnabled(context)) return null
        val files = listOf(p12File(context), rawKeyFile(context), rawChainFile(context))
            .filter { it.exists() }
        if (files.isEmpty()) return null
        return files.joinToString("|") { "${it.name}:${it.lastModified()}:${it.length()}" }
    }

    fun clear(context: Context) {
        p12File(context).delete()
        rawKeyFile(context).delete()
        rawChainFile(context).delete()
        File(dir(context), "$P12_NAME.tmp").delete()
        prefs(context).edit()
            .remove(PREF_PASSWORD)
            .remove(PREF_FORMAT)
            .putBoolean(PREF_ENABLED, false)
            .apply()
    }

    // --- Reading back the stored certificate ---------------------------------

    /** Returns the stored key/chain, or null when nothing usable is imported. Never throws. */
    fun material(context: Context): Material? {
        if (!hasCertificate(context)) return null
        return try {
            if (prefs(context).getString(PREF_FORMAT, FORMAT_P12) == FORMAT_RAW) {
                loadRaw(context)
            } else {
                loadPkcs12(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the imported client certificate: ${e.message}")
            null
        }
    }

    private fun loadPkcs12(context: Context): Material? {
        val file = p12File(context)
        if (!file.exists()) return null
        val password = password(context) ?: return null
        val keyStore = KeyStore.getInstance("PKCS12")
        file.inputStream().use { keyStore.load(it, password) }
        for (alias in keyStore.aliases()) {
            if (!keyStore.isKeyEntry(alias)) continue
            val key = keyStore.getKey(alias, password) as? PrivateKey ?: continue
            val chain = keyStore.getCertificateChain(alias)
                ?.filterIsInstance<X509Certificate>()
                .orEmpty()
            if (chain.isNotEmpty()) return Material(key, chain.toTypedArray())
        }
        return null
    }

    private fun loadRaw(context: Context): Material? {
        val keyFile = rawKeyFile(context)
        val chainFile = rawChainFile(context)
        if (!keyFile.exists() || !chainFile.exists()) return null
        val key = decodePkcs8(keyFile.readBytes())
        val factory = CertificateFactory.getInstance("X.509")
        val chain = factory.generateCertificates(ByteArrayInputStream(chainFile.readBytes()))
            .filterIsInstance<X509Certificate>()
        if (chain.isEmpty()) return null
        return Material(key, chain.toTypedArray())
    }

    private fun password(context: Context): CharArray? =
        prefs(context).getString(PREF_PASSWORD, null)?.takeIf { it.isNotEmpty() }?.toCharArray()

    // --- Import --------------------------------------------------------------

    /**
     * Imports a PKCS#12 (.p12/.pfx) file. [password] is the password of the container; the
     * private key has to use the same password (which is what `openssl pkcs12 -export` and the
     * Cloudflare export produce).
     *
     * @throws ClientCertException when the container or its password is unusable.
     */
    fun importPkcs12(context: Context, data: ByteArray, password: CharArray) {
        val keyStore = KeyStore.getInstance("PKCS12")
        try {
            keyStore.load(ByteArrayInputStream(data), password)
        } catch (e: Exception) {
            throw ClientCertException(
                "The PKCS#12 file could not be opened. Please check the password. If the password " +
                        "is correct the file may have been exported by a recent OpenSSL with AES-256 " +
                        "encryption that old Android versions cannot read - re-export it with " +
                        "`openssl pkcs12 -export -legacy` or import the .pem files instead. (${e.message})"
            )
        }

        for (alias in keyStore.aliases()) {
            if (!keyStore.isKeyEntry(alias)) continue
            val key = try {
                keyStore.getKey(alias, password) as? PrivateKey
            } catch (e: java.security.UnrecoverableKeyException) {
                throw ClientCertException(
                    "The private key inside the PKCS#12 file uses a different password than the file itself."
                )
            }
            val chain = keyStore.getCertificateChain(alias)
                ?.filterIsInstance<X509Certificate>()
                .orEmpty()
            if (key == null || chain.isEmpty()) continue
            if (!keyMatchesCertificate(key, chain.first())) {
                throw ClientCertException("The private key does not match the certificate in the PKCS#12 file.")
            }
            save(context, key, chain.toTypedArray())
            return
        }
        throw ClientCertException("The PKCS#12 file does not contain a private key with a certificate.")
    }

    /**
     * Imports PEM text blocks (the format the Cloudflare dashboard hands out). Returns the
     * certificate and private key blocks in any combination - they may be spread over one file
     * (certificate + key in the same file) or over the two selected files.
     *
     * @throws ClientCertException when the PEM data is incomplete or does not fit together.
     */
    fun importPem(context: Context, pems: List<String>) {
        val certificates = mutableListOf<X509Certificate>()
        var privateKey: PrivateKey? = null
        val certificateFactory = CertificateFactory.getInstance("X.509")

        for (text in pems) {
            for (match in PEM_PATTERN.findAll(text)) {
                val type = match.groupValues[1].trim().uppercase(Locale.US)
                val body = match.groupValues[2].replace(WHITESPACE, "")
                if (body.isEmpty()) continue
                val der = try {
                    Base64.decode(body, Base64.DEFAULT)
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable PEM block ($type): ${e.message}")
                    continue
                }
                when (type) {
                    "CERTIFICATE" -> {
                        val cert = certificateFactory.generateCertificate(ByteArrayInputStream(der))
                        if (cert is X509Certificate) certificates.add(cert)
                    }

                    "PRIVATE KEY" -> if (privateKey == null) privateKey = decodePkcs8(der)
                    "RSA PRIVATE KEY" -> if (privateKey == null) privateKey = decodePkcs8(wrapPkcs1Rsa(der))
                    "EC PRIVATE KEY" -> if (privateKey == null) privateKey = decodePkcs8(wrapSec1Ec(der))

                    "ENCRYPTED PRIVATE KEY" -> throw ClientCertException(
                        "The private key is encrypted and cannot be read. Please export an " +
                                "unencrypted key (openssl pkcs8 -topk8 -nocrypt) or use a .p12 file."
                    )
                }
            }
        }

        if (certificates.isEmpty()) {
            throw ClientCertException(
                "No certificate was found. Select the .pem/.crt file that contains " +
                        "-----BEGIN CERTIFICATE-----."
            )
        }
        val key = privateKey ?: throw ClientCertException(
            "No private key was found. Select the .pem/.key file that contains " +
                    "-----BEGIN PRIVATE KEY-----."
        )

        val chain = orderChain(certificates)
        if (!keyMatchesCertificate(key, chain.first())) {
            throw ClientCertException("The private key does not match the certificate you selected.")
        }
        save(context, key, chain.toTypedArray())
    }

    /** Sorts certificates so that the leaf (the certificate the key belongs to) comes first. */
    private fun orderChain(certificates: List<X509Certificate>): List<X509Certificate> {
        if (certificates.size <= 1) return certificates
        val remaining = certificates.toMutableList()
        var current = remaining.firstOrNull { candidate ->
            remaining.none { other ->
                other !== candidate && other.issuerX500Principal == candidate.subjectX500Principal
            }
        } ?: return certificates
        val ordered = mutableListOf<X509Certificate>()
        while (true) {
            ordered.add(current)
            remaining.remove(current)
            val issuer: X500Principal = current.issuerX500Principal
            current = remaining.firstOrNull { it.subjectX500Principal == issuer } ?: break
        }
        ordered.addAll(remaining)
        return ordered
    }

    /** Checks that [key] is really the private key of [certificate] by signing a test message. */
    private fun keyMatchesCertificate(key: PrivateKey, certificate: X509Certificate): Boolean {
        // A key of a different type than the certificate can never belong to it.
        if (normalizeAlgorithm(key.algorithm) != normalizeAlgorithm(certificate.publicKey.algorithm)) {
            return false
        }
        val algorithm = when (key.algorithm.uppercase(Locale.US)) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            "DSA" -> "SHA256withDSA"
            else -> return true // unknown algorithm: the TLS stack will tell us later
        }
        return try {
            val probe = byteArrayOf(0x49, 0x46, 0x52, 0x41, 0x4D, 0x45)
            val signer = Signature.getInstance(algorithm)
            signer.initSign(key)
            signer.update(probe)
            val signature = signer.sign()
            val verifier = Signature.getInstance(algorithm)
            verifier.initVerify(certificate.publicKey)
            verifier.update(probe)
            verifier.verify(signature)
        } catch (e: Exception) {
            // Some devices cannot run the probe (rare algorithm/provider combinations). Never
            // block the import because of that; a wrong key is reported by the server instead.
            Log.w(TAG, "Could not verify that the private key matches the certificate: ${e.message}")
            true
        }
    }

    private fun normalizeAlgorithm(algorithm: String): String =
        if (algorithm.equals("ECDSA", ignoreCase = true)) "EC" else algorithm.uppercase(Locale.US)

    // --- Writing -------------------------------------------------------------

    private fun save(context: Context, key: PrivateKey, chain: Array<X509Certificate>) {
        val directory = dir(context).apply { mkdirs() }
        rawKeyFile(context).delete()
        rawChainFile(context).delete()

        val password = randomPassword()
        val target = p12File(context)
        val temp = File(directory, "$P12_NAME.tmp")
        try {
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, password)
            keyStore.setKeyEntry(KEY_ALIAS, key, password, chain)
            temp.outputStream().use { keyStore.store(it, password) }
        } catch (e: Exception) {
            Log.w(TAG, "PKCS#12 storage unavailable, keeping the raw key instead: ${e.message}")
            temp.delete()
            saveRaw(context, key, chain)
            prefs(context).edit()
                .putString(PREF_FORMAT, FORMAT_RAW)
                .remove(PREF_PASSWORD)
                .putBoolean(PREF_ENABLED, true)
                .apply()
            verifyStored(context)
            return
        }

        target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            throw ClientCertException("The client certificate could not be saved on this device.")
        }
        prefs(context).edit()
            .putString(PREF_FORMAT, FORMAT_P12)
            .putString(PREF_PASSWORD, String(password))
            .putBoolean(PREF_ENABLED, true)
            .apply()
        verifyStored(context)
    }

    private fun saveRaw(context: Context, key: PrivateKey, chain: Array<X509Certificate>) {
        val directory = dir(context).apply { mkdirs() }
        File(directory, RAW_KEY_NAME).writeBytes(key.encoded)
        val out = ByteArrayOutputStream()
        chain.forEach { out.write(it.encoded) }
        File(directory, RAW_CHAIN_NAME).writeBytes(out.toByteArray())
    }

    private fun verifyStored(context: Context) {
        val stored = material(context)
            ?: throw ClientCertException("The client certificate could not be read back after saving.")
        if (stored.chain.isEmpty()) {
            throw ClientCertException("The client certificate could not be read back after saving.")
        }
    }

    private fun randomPassword(): CharArray {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP).toCharArray()
    }

    // --- Consumers -----------------------------------------------------------

    /**
     * Builds the key managers for the SSLContext used by OkHttp/Retrofit. Returns null when mTLS
     * is disabled, so callers can fall back to the platform defaults.
     */
    fun keyManagers(context: Context): Array<KeyManager>? {
        if (!isEnabled(context)) return null
        val material = material(context) ?: return null
        return arrayOf(ClientKeyManager(material.privateKey, material.chain))
    }

    /**
     * Answers a WebView client certificate request. The certificate is only presented to
     * [allowedHost] (the configured ImmichFrame server) so that it is never leaked to an
     * unrelated host that happens to be embedded in the page.
     */
    fun proceedWithClientCertificate(
        context: Context,
        request: ClientCertRequest,
        allowedHost: String? = null,
    ) {
        val expectedHost = allowedHost?.takeIf { it.isNotBlank() }
        if (expectedHost != null && !request.host.equals(expectedHost, ignoreCase = true)) {
            Log.i(TAG, "Ignoring client certificate request from unexpected host '${request.host}'")
            request.cancel()
            return
        }

        val material = if (isEnabled(context)) material(context) else null
        if (material == null) {
            Log.i(TAG, "Server '${request.host}' asked for a client certificate, but none is configured")
            request.cancel()
            return
        }

        try {
            request.proceed(material.privateKey, material.chain)
            Log.i(TAG, "Presented the client certificate to '${request.host}:${request.port}'")
        } catch (e: Exception) {
            Log.w(TAG, "Could not present the client certificate to '${request.host}': ${e.message}")
            request.cancel()
        }
    }

    private class ClientKeyManager(
        private val privateKey: PrivateKey,
        private val chain: Array<X509Certificate>,
    ) : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
            arrayOf(KEY_ALIAS)

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? {
            if (keyType != null && keyType.isNotEmpty() && !keyType.any { matchesKeyType(it) }) return null
            return KEY_ALIAS
        }

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == KEY_ALIAS) chain else null

        override fun getPrivateKey(alias: String?): PrivateKey? =
            if (alias == KEY_ALIAS) privateKey else null

        private fun matchesKeyType(keyType: String): Boolean {
            val algorithm = privateKey.algorithm
            if (keyType.equals(algorithm, ignoreCase = true)) return true
            // "EC" and "ECDSA" are used interchangeably by the different TLS stacks.
            return algorithm.equals("EC", ignoreCase = true) && keyType.equals("ECDSA", ignoreCase = true) ||
                    algorithm.equals("ECDSA", ignoreCase = true) && keyType.equals("EC", ignoreCase = true)
        }
    }

    // --- Description ---------------------------------------------------------

    /** Human readable summary shown in the settings screen, or null when nothing is imported. */
    fun describe(context: Context): String? {
        if (!hasCertificate(context)) return null
        val material = material(context)
            ?: return "The stored client certificate could not be read. Please import it again."
        val leaf = material.chain.firstOrNull() ?: return "The stored client certificate is incomplete."
        val builder = StringBuilder()
        builder.append("Subject: ").append(friendlyName(leaf.subjectX500Principal))
        builder.append("\nIssuer: ").append(friendlyName(leaf.issuerX500Principal))
        builder.append("\nValid until: ")
            .append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(leaf.notAfter))
        if (leaf.notAfter.before(Date())) builder.append("  (EXPIRED)")
        builder.append("\nKey: ").append(describeKey(material.privateKey))
        if (material.chain.size > 1) {
            builder.append("\nChain: ").append(material.chain.size).append(" certificates")
        }
        return builder.toString()
    }

    private fun friendlyName(name: X500Principal): String {
        val rfc2253 = name.getName(X500Principal.RFC2253)
        val cn = Regex("(?:^|,)CN=((?:\\\\.|[^,])*)").find(rfc2253)?.groupValues?.get(1)?.trim()
        return cn?.takeIf { it.isNotEmpty() } ?: name.name
    }

    private fun describeKey(key: PrivateKey): String {
        val size = when (key) {
            is java.security.interfaces.RSAPrivateKey -> " ${key.modulus.bitLength()}-bit"
            is java.security.interfaces.ECPrivateKey ->
                runCatching { " ${key.params.curve.field.fieldSize}-bit" }.getOrDefault("")

            else -> ""
        }
        return "${key.algorithm}$size"
    }

    // --- PEM / DER helpers ---------------------------------------------------

    private val PEM_PATTERN =
        Regex("-----BEGIN ([A-Z0-9 ]+)-----\\s*(.*?)\\s*-----END \\1-----", RegexOption.DOT_MATCHES_ALL)

    private val WHITESPACE = Regex("\\s")

    /** OID 1.2.840.113549.1.1.1 (rsaEncryption), DER encoded content octets. */
    private val OID_RSA_ENCRYPTION = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01
    )

    /** OID 1.2.840.10045.2.1 (id-ecPublicKey), DER encoded content octets. */
    private val OID_EC_PUBLIC_KEY = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01
    )

    private fun decodePkcs8(der: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(der)
        for (algorithm in arrayOf("RSA", "EC", "DSA")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec)
            } catch (_: Exception) {
                // try the next algorithm
            }
        }
        throw ClientCertException(
            "The private key format is not supported. Please export it as PKCS#8 " +
                    "(openssl pkcs8 -topk8 -nocrypt) or provide a .p12 file."
        )
    }

    /** Wraps a PKCS#1 RSAPrivateKey into the PKCS#8 PrivateKeyInfo structure. */
    private fun wrapPkcs1Rsa(pkcs1: ByteArray): ByteArray = Der.sequence(
        Der.integer(0),
        Der.sequence(Der.oid(OID_RSA_ENCRYPTION), Der.nullValue()),
        Der.octetString(pkcs1)
    )

    /**
     * Wraps a SEC1/RFC5915 ECPrivateKey into PKCS#8. The curve is taken from the optional
     * `parameters [0]` field; without it the key cannot be used and an error is raised.
     */
    private fun wrapSec1Ec(sec1: ByteArray): ByteArray {
        val (_, contentStart, contentEnd) = Der.read(sec1, 0)
        var offset = contentStart
        var parameters: ByteArray? = null
        while (offset < contentEnd) {
            val (tag, start, end) = Der.read(sec1, offset)
            if (tag == 0xA0) parameters = sec1.copyOfRange(start, end)
            offset = end
        }
        val curve = parameters ?: throw ClientCertException(
            "The EC private key does not contain its curve parameters. Please export it as PKCS#8 " +
                    "(openssl pkcs8 -topk8 -nocrypt) or provide a .p12 file."
        )
        return Der.sequence(
            Der.integer(0),
            Der.sequence(Der.oid(OID_EC_PUBLIC_KEY), curve),
            Der.octetString(sec1)
        )
    }

    /** Minimal DER writer/reader - just enough for the two PKCS#8 wrappers above. */
    private object Der {
        fun sequence(vararg parts: ByteArray): ByteArray {
            val body = ByteArrayOutputStream()
            parts.forEach { body.write(it) }
            return tlv(0x30, body.toByteArray())
        }

        fun integer(value: Int): ByteArray = tlv(0x02, byteArrayOf(value.toByte()))

        fun oid(content: ByteArray): ByteArray = tlv(0x06, content)

        fun octetString(data: ByteArray): ByteArray = tlv(0x04, data)

        fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

        private fun tlv(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            out.write(encodeLength(content.size))
            out.write(content)
            return out.toByteArray()
        }

        private fun encodeLength(length: Int): ByteArray = when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
            length < 0x10000 -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
            else -> byteArrayOf(
                0x83.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte()
            )
        }

        /** Reads the TLV that starts at [offset] and returns (tag, contentStart, contentEnd). */
        fun read(data: ByteArray, offset: Int): Triple<Int, Int, Int> {
            if (offset + 2 > data.size) throw ClientCertException("Malformed certificate data.")
            val tag = data[offset].toInt() and 0xFF
            var index = offset + 1
            var length = data[index].toInt() and 0xFF
            index++
            if (length and 0x80 != 0) {
                val count = length and 0x7F
                if (count !in 1..4 || index + count > data.size) {
                    throw ClientCertException("Malformed certificate data.")
                }
                length = 0
                repeat(count) {
                    length = (length shl 8) or (data[index].toInt() and 0xFF)
                    index++
                }
            }
            val contentStart = index
            val contentEnd = index + length
            if (contentEnd > data.size) throw ClientCertException("Malformed certificate data.")
            return Triple(tag, contentStart, contentEnd)
        }
    }
}
