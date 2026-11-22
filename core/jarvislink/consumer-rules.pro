# HiveMQ MQTT client (uses Netty + JCTools reflection). Rules from the HiveMQ
# client docs for Android; only relevant if the app ever enables minify.
-keepclassmembernames class io.netty.** { *; }
-keepclassmembers class org.jctools.** { *; }
-dontwarn io.netty.**
-dontwarn org.jctools.**
-dontwarn com.hivemq.client.internal.**
-dontwarn reactor.blockhound.**
