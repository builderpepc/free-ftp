package com.freeftp.core.testing

import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** Builds the self-signed keystore the FTPS test server presents. */
object TestCertificates {

    const val KEYSTORE_PASSWORD: String = "changeit"

    init {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(BouncyCastleProvider())
        }
    }

    /** Writes a PKCS#12 keystore containing one self-signed `CN=localhost` certificate. */
    fun writeSelfSignedKeystore(target: Path): Path {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Principal("CN=localhost")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 365L * 86_400_000L),
            subject,
            keyPair.public,
        )
        // Real certificates carry a SAN; without one, a hostname check fails before the
        // trust path is even considered, which would make the "untrusted certificate"
        // test pass for the wrong reason.
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(
                arrayOf(
                    GeneralName(GeneralName.dNSName, "localhost"),
                    GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                )
            ),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate: X509Certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(
                "ftps",
                keyPair.private,
                KEYSTORE_PASSWORD.toCharArray(),
                arrayOf<java.security.cert.Certificate>(certificate),
            )
        }
        FileOutputStream(target.toFile()).use { keyStore.store(it, KEYSTORE_PASSWORD.toCharArray()) }
        return target
    }
}
