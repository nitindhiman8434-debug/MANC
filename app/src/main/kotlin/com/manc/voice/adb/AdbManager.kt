package com.manc.voice.adb

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import io.github.muntashirakon.adb.android.AndroidUtils
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/**
 * The app's own ADB client. It pairs with and connects to the device's own
 * wireless-debugging daemon (127.0.0.1) and opens shell / localabstract streams
 * — the same capability Shizuku uses internally, but embedded, so no separate
 * Shizuku app is required.
 *
 * Key + certificate are generated once and persisted in filesDir. Based on the
 * libadb-android sample (dual GPL-3.0-or-later OR Apache-2.0); see NOTICE.
 */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        api = Build.VERSION.SDK_INT
        val dir = context.filesDir
        val keyFile = File(dir, "adb_private.key")
        val certFile = File(dir, "adb_cert.pem")
        val existingKey = readPrivateKey(keyFile)
        val existingCert = readCertificate(certFile)
        if (existingKey != null && existingCert != null) {
            privateKey = existingKey
            certificate = existingCert
        } else {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val pair = gen.generateKeyPair()
            privateKey = pair.private
            certificate = generateCertificate(pair.public, privateKey)
            keyFile.writeBytes(privateKey.encoded)
            writeCertificate(certFile, certificate)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "MANC Voice"

    /** Open a stream to an abstract-namespace LocalServerSocket the shell server bound. */
    fun openAbstract(name: String): AdbStream =
        openStream(LocalServices.LOCAL_UNIX_SOCKET_ABSTRACT, name)

    fun openShell(command: String): AdbStream =
        openStream(LocalServices.SHELL, command)

    companion object {
        @Volatile private var instance: AdbManager? = null

        fun getInstance(context: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }

        fun hostAddress(context: Context): String = AndroidUtils.getHostIpAddress(context)

        private fun readPrivateKey(f: File): PrivateKey? = try {
            if (!f.exists()) null
            else KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(f.readBytes()))
        } catch (_: Throwable) { null }

        private fun readCertificate(f: File): Certificate? = try {
            if (!f.exists()) null
            else f.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } catch (_: Throwable) { null }

        private fun writeCertificate(f: File, cert: Certificate) {
            val encoder = android.sun.misc.BASE64Encoder()
            f.outputStream().use { os ->
                os.write(android.sun.security.provider.X509Factory.BEGIN_CERT.toByteArray())
                os.write('\n'.code)
                encoder.encode(cert.encoded, os)
                os.write('\n'.code)
                os.write(android.sun.security.provider.X509Factory.END_CERT.toByteArray())
            }
        }

        private fun generateCertificate(publicKey: PublicKey, privateKey: PrivateKey): Certificate {
            val subject = "CN=MANC Voice"
            val algorithm = "SHA512withRSA"
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 10_000L * 24 * 60 * 60 * 1000) // ~27 years
            val extensions = CertificateExtensions()
            extensions.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
            val x500 = X500Name(subject)
            extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            val validity = CertificateValidity(notBefore, notAfter)
            val info = X509CertInfo()
            info.set("version", CertificateVersion(2))
            info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            info.set("subject", CertificateSubjectName(x500))
            info.set("key", CertificateX509Key(publicKey))
            info.set("validity", validity)
            info.set("issuer", CertificateIssuerName(x500))
            info.set("extensions", extensions)
            val cert = X509CertImpl(info)
            cert.sign(privateKey, algorithm)
            return cert
        }
    }
}
