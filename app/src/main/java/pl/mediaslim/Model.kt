package pl.mediaslim

import android.net.Uri
import java.util.Locale

/** Film z galerii razem z decyzja, co z nim zrobic. */
data class Film(
    val id: Long,
    val uri: Uri,
    val nazwa: String,
    val rozmiar: Long,
    val czasMs: Long,
    val sciezkaWzgledna: String,
    val dataZrobienia: Long,
    val dataModyfikacji: Long,
    val szerokosc: Int,
    val wysokosc: Int,
    val obrot: Int,
    val fps: Float,
    val kodek: String,
    val maAudio: Boolean,
    val mbps: Double,
    /** 0 = nie skalujemy; inaczej docelowa dlugosc krotszego boku */
    val docelowyKrotszyBok: Int,
    val bitrateDocelowy: Int,
    val szacowanyRozmiar: Long
) {
    val klucz: String get() = kluczPliku(id, rozmiar, dataModyfikacji)
    val dluzszyBok: Int get() = maxOf(szerokosc, wysokosc)
    val krotszyBok: Int get() = minOf(szerokosc, wysokosc)
    val zysk: Long get() = (rozmiar - szacowanyRozmiar).coerceAtLeast(0L)
}

/** Plik juz zakodowany, czekajacy na podmiane. */
data class Gotowy(
    val klucz: String,
    val uri: Uri,
    val nazwa: String,
    val rozmiar: Long,
    val rozmiarWyniku: Long,
    val plikWyniku: String,
    val sciezkaWzgledna: String,
    val czasMs: Long,
    val maAudio: Boolean,
    val szerokosc: Int,
    val wysokosc: Int,
    val obrot: Int,
    val dataZrobienia: Long,
    val dataModyfikacji: Long,
    val status: String,
    val powod: String
) {
    val procent: Int
        get() = if (rozmiar > 0) Math.round(100.0 * rozmiarWyniku / rozmiar).toInt() else 0
}

/** Oryginal przeniesiony do kosza systemowego. */
data class WKoszu(val klucz: String, val uri: Uri, val nazwa: String, val rozmiar: Long)

fun kluczPliku(id: Long, rozmiar: Long, dataModyfikacji: Long): String =
    "$id:$rozmiar:$dataModyfikacji"

fun waga(bajty: Long): String {
    val mb = bajty / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0)
    else String.format(Locale.US, "%.0f MB", mb)
}

fun czasTekst(sekundy: Long): String {
    if (sekundy <= 0) return "—"
    val g = sekundy / 3600
    val m = (sekundy % 3600) / 60
    val s = sekundy % 60
    return when {
        g > 0 -> "$g h $m min"
        m > 0 -> "$m min $s s"
        else -> "$s s"
    }
}
