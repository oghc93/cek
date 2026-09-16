package com.lite.xraylite.model

/** Info tambahan yang ditampilkan di list profil & dashboard, di luar ServerConfig statis. */
data class ServerStatus(
    val configId: String,
    val pingMs: Int? = null,        // null = belum di-test
    val isActive: Boolean = false
)

/** Statistik pemakaian data selama sesi berjalan (reset tiap connect). */
data class DataUsage(
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val connectedSinceMillis: Long = 0
) {
    fun formatUpload(): String = formatBytes(uploadBytes)
    fun formatDownload(): String = formatBytes(downloadBytes)

    private fun formatBytes(b: Long): String {
        val kb = b / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> "%.2f GB".format(gb)
            mb >= 1 -> "%.1f MB".format(mb)
            kb >= 1 -> "%.0f KB".format(kb)
            else -> "$b B"
        }
    }
}
