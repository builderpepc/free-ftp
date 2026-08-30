package com.freeftp.app

import android.app.Application
import android.content.Context
import com.freeftp.app.security.AndroidKeystoreCipher
import com.freeftp.app.storage.DownloadLocationStore
import com.freeftp.core.sftp.FileHostKeyStore
import com.freeftp.core.sftp.HostKeyStore
import com.freeftp.core.store.ServerProfileRepository
import java.io.File
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * The app's whole dependency graph.
 *
 * FreeFTP has four collaborators and no need for a DI framework; a plain container keeps
 * startup instant and the wiring readable.
 */
class AppContainer(context: Context) {

    private val filesDir: File = context.filesDir

    val profiles: ServerProfileRepository by lazy {
        ServerProfileRepository(File(filesDir, "servers.dat"), AndroidKeystoreCipher.create())
    }

    val hostKeys: HostKeyStore by lazy { FileHostKeyStore(File(filesDir, "known_host_keys.txt")) }

    /** The connection the browser and the transfer queue share. */
    val session: SessionHolder by lazy { SessionHolder(hostKeys) }

    /** Where downloads land, and how to build a destination for each file. */
    val downloads: DownloadLocationStore = DownloadLocationStore(context)
}

class FreeFtpApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installFullBouncyCastle()
        container = AppContainer(this)
    }

    /**
     * Replaces Android's cut-down `BC` provider with the full BouncyCastle bundled here.
     *
     * Android ships a repackaged, heavily trimmed BouncyCastle registered under the name
     * `BC`. SSHJ asks for that name by design, so without this it silently gets the
     * trimmed one and modern SSH key exchange dies with "no such algorithm: X25519 for
     * provider BC". Adding our copy is not enough on its own — `addProvider` is a no-op
     * when a provider of that name is already installed, so the platform one has to go
     * first. It is appended rather than inserted at the front so TLS keeps using
     * Conscrypt, which is both faster and better maintained.
     */
    private fun installFullBouncyCastle() {
        val installed = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (installed != null && installed.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as FreeFtpApp).container
