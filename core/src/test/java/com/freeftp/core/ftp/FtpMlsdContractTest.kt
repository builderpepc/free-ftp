package com.freeftp.core.ftp

/**
 * The FTP contract again, this time over `MLSD` rather than `LIST -a`.
 *
 * The two listing commands return different text, are parsed by different code and
 * disagree about hidden files, so the whole contract is worth re-running across both
 * rather than trusting that one implies the other.
 */
class FtpMlsdContractTest : FtpClientContractTest() {
    override val showHiddenFiles: Boolean get() = false
}
