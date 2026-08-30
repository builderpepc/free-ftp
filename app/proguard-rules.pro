# FreeFTP R8 rules.
#
# The two protocol libraries resolve a lot by name rather than by reference, so R8
# cannot see those uses and would otherwise strip or rename the classes involved.

# --- BouncyCastle ------------------------------------------------------------
# The JCE provider registers every algorithm through a map of class *names*; each
# implementation is then instantiated reflectively, so none of them look reachable.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# --- SSHJ --------------------------------------------------------------------
# Key/cipher/MAC factories are looked up by their SSH wire names, and SSHJ probes
# for optional classes it may not be shipped with.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.**

# --- Apache Commons Net ------------------------------------------------------
# DefaultFTPFileEntryParserFactory can resolve a listing parser from a class name.
-keep class org.apache.commons.net.ftp.parser.** { *; }
-dontwarn org.apache.commons.net.**

# --- Logging -----------------------------------------------------------------
-dontwarn org.slf4j.**
-keep class uk.uuid.slf4j.android.** { *; }

# Optional dependencies that these libraries reference but we do not ship.
-dontwarn javax.naming.**
-dontwarn java.beans.**
-dontwarn org.ietf.jgss.**
