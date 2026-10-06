package pl.mediaslim

import android.net.Uri
import java.util.Locale

/**
 * Plik z galerii razem z decyzja, co z nim zrobic.
 *
 * Jedna klasa obsluguje film i zdjecie, bo caly dalszy ciag - kolejka,
 * usluga, weryfikacja, podmiana, kosz - jest dla obu taki sam. Rozni sie
 * tylko samo kodowanie. Pola nieuzywane przy zdjeciach (czas, fps, dzwiek)
 * maja wartosci domyslne.
 */
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
    /** 0 = nie skalujemy; inaczej docelowa dlugosc krotszego boku (wideo) */
    val docelowyKrotszyBok: Int,
    val bitrateDocelowy: Int,
    val szacowanyRozmiar: Long,
    /** Zdjecie zamiast filmu - inna sciezka kodowania i inny typ MIME. */
    val czyZdjecie: Boolean = false,
    /** Docelowe wymiary zdjecia, juz po uwzglednieniu obrotu z EXIF. */
    val docelowaSzer: Int = 0,
    val docelowaWys: Int = 0,
    /** Jakosc zapisu JPEG dla zdjec. */
    val jakosc: Int = 92
) {
    val klucz: String get() = kluczPliku(id, rozmiar, dataModyfikacji)
    val dluzszyBok: Int get() = maxOf(szerokosc, wysokosc)
    val krotszyBok: Int get() = minOf(szerokosc, wysokosc)
    val zysk: Long get() = (rozmiar - szacowanyRozmiar).coerceAtLeast(0L)

    /** Wymiary tak, jak pokaze je galeria - po uwzglednieniu obrotu. */
    val pokazanaSzer: Int get() = if (obrot == 90 || obrot == 270) wysokosc else szerokosc
    val pokazanaWys: Int get() = if (obrot == 90 || obrot == 270) szerokosc else wysokosc

    val rozszerzenie: String get() = if (czyZdjecie) "jpg" else "mp4"
    val typMime: String get() = if (czyZdjecie) "image/jpeg" else "video/mp4"
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
    val powod: String,
    val czyZdjecie: Boolean = false
) {
    val procent: Int
        get() = if (rozmiar > 0) Math.round(100.0 * rozmiarWyniku / rozmiar).toInt() else 0

    val rozszerzenie: String get() = if (czyZdjecie) "jpg" else "mp4"
    val typMime: String get() = if (czyZdjecie) "image/jpeg" else "video/mp4"
}

/** Oryginal przeniesiony do kosza systemowego. */
data class WKoszu(
    val klucz: String,
    val uri: Uri,
    val nazwa: String,
    val rozmiar: Long,
    val czyZdjecie: Boolean = false
)

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

/**
 * Docelowe wymiary zdjecia. Zwraca (szerokosc, wysokosc) albo null,
 * gdy plik juz miesci sie w ramce i nie ma czego zmniejszac.
 *
 * Ramka jest KWADRATOWA: ograniczamy dluzszy bok, nigdy szerokosc
 * i wysokosc osobno. Dzieki temu zdjecie pionowe nie jest duszone -
 * to ta sama zasada co przy wideo i ta sama, ktora kosztowala 38
 * zduszonych filmow w wersji na komputer.
 *
 * Wyjatek: przy bardzo wydluzonym kadrze (zrzut ekranu z przewijaniem,
 * panorama) ograniczamy KROTSZY bok. Zrzut 1080x12000 zmniejszony do
 * 2500 px na dluzszym boku ma 225 px szerokosci i nie da sie go odczytac.
 */
fun docelowePikseleZdjecia(
    szer: Int,
    wys: Int,
    maxBok: Int,
    progProporcji: Double = 2.5
): Pair<Int, Int>? {
    if (szer <= 0 || wys <= 0) return null
    val dluzszy = maxOf(szer, wys)
    val krotszy = minOf(szer, wys)
    val proporcja = dluzszy.toDouble() / krotszy

    if (proporcja >= progProporcji) {
        if (krotszy <= maxBok) return null
        val s = maxBok.toDouble() / krotszy
        return Pair(
            maxOf(1, Math.round(szer * s).toInt()),
            maxOf(1, Math.round(wys * s).toInt())
        )
    }
    if (dluzszy <= maxBok) return null
    val s = maxBok.toDouble() / dluzszy
    return Pair(
        maxOf(1, Math.round(szer * s).toInt()),
        maxOf(1, Math.round(wys * s).toInt())
    )
}
