# libtorrent's native code calls back into its Java classes by name.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**
