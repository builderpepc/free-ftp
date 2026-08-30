package com.freeftp.core

import java.io.IOException

/**
 * Base class for every failure the transfer layer reports.
 *
 * Messages are user-facing, so implementations must never interpolate credentials
 * into them (see `ErrorMappingTest`, case 10.5).
 */
open class RemoteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The TCP/TLS connection could not be established or was lost. */
class TransportException(message: String, cause: Throwable? = null) : RemoteException(message, cause)

/** The server rejected the credentials. */
class AuthenticationException(message: String, cause: Throwable? = null) : RemoteException(message, cause)

/** TLS negotiation failed, typically an untrusted certificate. */
class TlsException(message: String, cause: Throwable? = null) : RemoteException(message, cause)

/** The SSH host key differs from the one previously stored for this host. */
class HostKeyChangedException(
    val host: String,
    val expectedFingerprint: String,
    val actualFingerprint: String,
) : RemoteException(
    "Host key for $host changed: expected $expectedFingerprint but got $actualFingerprint"
)

/** No host key is stored for this host and the verifier is not allowed to trust it automatically. */
class UnknownHostKeyException(val host: String, val fingerprint: String) :
    RemoteException("Unknown host key for $host: $fingerprint")

class RemoteFileNotFoundException(val path: String, cause: Throwable? = null) :
    RemoteException("No such file or directory: $path", cause)

class RemoteFileAlreadyExistsException(val path: String, cause: Throwable? = null) :
    RemoteException("Already exists: $path", cause)

class NotADirectoryException(val path: String, cause: Throwable? = null) :
    RemoteException("Not a directory: $path", cause)

class DirectoryNotEmptyException(val path: String, cause: Throwable? = null) :
    RemoteException("Directory is not empty: $path", cause)

class PermissionDeniedException(val path: String, cause: Throwable? = null) :
    RemoteException("Permission denied: $path", cause)

class QuotaExceededException(val path: String, cause: Throwable? = null) :
    RemoteException("Insufficient storage on the server for: $path", cause)

class InvalidFileNameException(val path: String, cause: Throwable? = null) :
    RemoteException("The server rejected the name: $path", cause)

/** The server does not implement a command the operation needs (e.g. `MFMT`). */
class UnsupportedFeatureException(val feature: String, cause: Throwable? = null) :
    RemoteException("The server does not support $feature", cause)

/** An operation was attempted on a client that is not connected. */
class NotConnectedException : RemoteException("Not connected")

/** A transfer was stopped by the caller. */
class TransferCancelledException(message: String = "Transfer cancelled") : RemoteException(message)

/** The server returned an error reply that has no more specific mapping. */
class ProtocolFailureException(val replyCode: Int, val replyText: String, cause: Throwable? = null) :
    RemoteException(
        if (replyText.isBlank()) "Server error $replyCode" else "Server error $replyCode: $replyText",
        cause,
    )
