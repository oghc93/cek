# Binding gomobile (paket "go" + "libv2ray" dari libv2ray.aar) sangat bergantung
# pada nama kelas/metode persis buat resolve method JNI-nya -- kalau di-rename/di-strip
# R8, native call-nya bisa gagal diam-diam atau UnsatisfiedLinkError. Aman-in semuanya.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**

# sshj + dependensi opsionalnya (bouncycastle utk key format tertentu, slf4j sbg
# logging facade) -- reflection-heavy, dan beberapa dependensi opsional memang
# sengaja tidak disertakan (cukup di-dontwarn, bukan error).
-keep class net.schmizz.sshj.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn net.i2p.crypto.eddsa.**

# Gson pakai reflection buat baca/isi field data class saat parse payload vmess://,
# dan sekarang juga dipakai ServerRepository buat persist daftar server ke SharedPreferences
# lewat TypeToken<List<ServerConfig>>() -- signature generic HARUS dipertahankan, kalau
# tidak Gson gagal deserialize List-nya di build release/minified (isMinifyEnabled=true).
-keepclassmembers class com.lite.xraylite.model.** { *; }
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**
