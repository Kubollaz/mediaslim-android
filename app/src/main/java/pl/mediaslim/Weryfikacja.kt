package pl.mediaslim

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs

/**
 * Piec testow, ktore plik musi przejsc, zanim podmienimy nim oryginal.
 * Kazdy z nich powstal po konkretnej wpadce na wersji na Windows.
 */
object Weryfikacja {

    data class Opis(
        val szerokosc: Int,
        val wysokosc: Int,
        val obrot: Int,
        val maAudio: Boolean,
        val czasMs: Long,
        val maDate: Boolean
    ) {
        /** Wymiary tak, jak pokaze je odtwarzacz - po uwzglednieniu obrotu. */
        val pokazaneSzer: Int get() = if (obrot == 90 || obrot == 270) wysokosc else szerokosc
        val pokazaneWys: Int get() = if (obrot == 90 || obrot == 270) szerokosc else wysokosc
        val uklad: String
            get() = when {
                pokazaneSzer > pokazaneWys -> "poziomy"
                pokazaneWys > pokazaneSzer -> "pionowy"
                else -> "kwadrat"
            }
    }

    fun opisz(sciezka: String): Opis? {
        var szer = 0
        var wys = 0
        var obrot = 0
        var maAudio = false
        val ex = MediaExtractor()
        try {
            ex.setDataSource(sciezka)
            var znalezioneWideo = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && !znalezioneWideo) {
                    znalezioneWideo = true
                    szer = f.getInteger(MediaFormat.KEY_WIDTH)
                    wys = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) {
                        obrot = f.getInteger(MediaFormat.KEY_ROTATION)
                    }
                }
                if (mime.startsWith("audio/")) maAudio = true
            }
            if (!znalezioneWideo) return null
        } catch (e: Exception) {
            return null
        } finally {
            try {
                ex.release()
            } catch (e: Exception) {
                // nic
            }
        }

        var czasMs = 0L
        var maDate = false
        try {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(sciezka)
                czasMs = mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                val obrotMeta = mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull()
                if (obrotMeta != null && obrot == 0) obrot = obrotMeta
                maDate = !mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    .isNullOrBlank()
            }
        } catch (e: Exception) {
            // zostaje to, co udalo sie odczytac z MediaExtractor
        }

        return Opis(szer, wys, obrot, maAudio, czasMs, maDate)
    }

    /** Zwraca null, gdy wszystko w porzadku, albo powod odrzucenia. */
    fun sprawdz(film: Film, plik: File, zrodloMaDate: Boolean): String? {
        if (!plik.exists() || plik.length() == 0L) return "plik wynikowy nie powstał"
        if (plik.length() >= film.rozmiar) {
            val proc = Math.round(100.0 * plik.length() / film.rozmiar)
            return "wynik nie jest mniejszy ($proc% oryginału)"
        }

        val w = opisz(plik.absolutePath) ?: return "nie da się odczytać wyniku"

        if (film.czasMs > 0 && w.czasMs > 0) {
            val roznica = abs(w.czasMs - film.czasMs) / 1000.0
            if (roznica > 2.0) {
                return "różna długość: ${film.czasMs / 1000} s vs ${w.czasMs / 1000} s"
            }
        }

        if (film.maAudio && !w.maAudio) return "BRAK ŚCIEŻKI DŹWIĘKU"

        // Orientacja liczona tak, jak zobaczy ja galeria: piksele plus obrot.
        val zrodloSzer = if (film.obrot == 90 || film.obrot == 270) film.wysokosc else film.szerokosc
        val zrodloWys = if (film.obrot == 90 || film.obrot == 270) film.szerokosc else film.wysokosc
        val ukladZrodla = when {
            zrodloSzer > zrodloWys -> "poziomy"
            zrodloWys > zrodloSzer -> "pionowy"
            else -> "kwadrat"
        }
        if (ukladZrodla != w.uklad) {
            return "OBRÓCONE: oryginał $ukladZrodla ${zrodloSzer}x$zrodloWys, " +
                "wynik ${w.uklad} ${w.pokazaneSzer}x${w.pokazaneWys}"
        }
        val propZrodla = zrodloSzer.toDouble() / zrodloWys
        val propWyniku = w.pokazaneSzer.toDouble() / w.pokazaneWys
        if (abs(propZrodla - propWyniku) / propZrodla > 0.03) {
            return "ZNIEKSZTAŁCONE: proporcje %.3f -> %.3f".format(propZrodla, propWyniku)
        }

        if (zrodloMaDate && !w.maDate) return "zgubiona data nagrania"

        return null
    }
}
