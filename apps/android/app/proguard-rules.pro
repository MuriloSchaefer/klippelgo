# What R8 cannot see is reached: code found by reflection or called from native code. The rest is shrunk.

# Names are kept. Stack traces from a release stay readable, and the libraries below look classes up by name.
-dontobfuscate
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions

# The Rust core. JNA reads the UniFFI bindings' structures and callbacks by reflection, and the core calls back
# into them.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.klippelgo.core.** { *; }
-dontwarn java.awt.**

# web3j decodes ABI values by building types from their generic signatures and constructors: TypeReference
# subclasses, the abi datatypes, and structs such as SmartAccount.Execution. Its JSON-RPC responses are read by
# Jackson into its own classes.
-keep class org.web3j.** { *; }
-keep class * extends org.web3j.abi.TypeReference { *; }
-keep class * extends org.web3j.abi.datatypes.Type { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn org.web3j.**
-dontwarn com.fasterxml.jackson.databind.ext.**

# BouncyCastle is a security provider: its algorithms are found by name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Valhalla's JNI wrapper calls into its Kotlin side, and its request and response models are read by Moshi.
-keep class com.valhalla.** { *; }

# Libraries that refer to classes the app does not ship.
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn io.reactivex.**
-dontwarn jnr.**
-dontwarn java.lang.management.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn groovy.lang.**
-dontwarn reactor.blockhound.**
