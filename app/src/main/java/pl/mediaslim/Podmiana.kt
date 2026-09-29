package pl.mediaslim

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Podmiana pliku w galerii - najostrozniejsza czesc calej aplikacji.
 *
 * Kolejnosc jest taka sama jak na Windows i w kazdym momencie istnieje
 * przynajmniej jedna kompletna kopia materialu:
 *
 *   1. kopiujemy wynik do galerii pod nazwa tymczasowa (oryginal caly czas jest)
 *   2. oryginal idzie do kosza systemowego - jednym pytaniem na cala paczke
 *   3. plik tymczasowy dostaje docelowa nazwe
 *
 * Miejsce odzyskujesz dopiero po oproznieniu kosza - tak samo jak na komputerze.
 */
object Podmiana {

    data class Para(val wpis: Gotowy, val nowyUri: Uri, val docelowaNazwa: String)

    private fun kolekcja(): Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Krok 1: wrzuca gotowe pliki do galerii jako ukryte (pending). */
    fun przygotuj(ctx: Context, wpisy: List<Gotowy>, postep: (Int, String) -> Unit): List<Para> {
        val pary = ArrayList<Para>()
        wpisy.forEachIndexed { indeks, wpis ->
            postep(indeks + 1, wpis.nazwa)
            val plik = File(wpis.plikWyniku)
            if (!plik.exists()) return@forEachIndexed

            val rdzen = wpis.nazwa.substringBeforeLast('.', wpis.nazwa)
            val tymczasowa = "$rdzen.mediaslim-tmp.mp4"
            val docelowa = "$rdzen.mp4"

            val dane = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, tymczasowa)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, wpis.sciezkaWzgledna)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (wpis.dataZrobienia > 0) {
                    put(MediaStore.Video.Media.DATE_TAKEN, wpis.dataZrobienia)
                }
            }

            val nowy = try {
                ctx.contentResolver.insert(kolekcja(), dane)
            } catch (e: Exception) {
                null
            } ?: return@forEachIndexed

            val skopiowane = try {
                ctx.contentResolver.openOutputStream(nowy, "w").use { wyjscie ->
                    if (wyjscie == null) 0L
                    else plik.inputStream().use { wejscie -> wejscie.copyTo(wyjscie) }
                }
            } catch (e: Exception) {
                0L
            }

            if (skopiowane < plik.length()) {
                try {
                    ctx.contentResolver.delete(nowy, null, null)
                } catch (e: Exception) {
                    // nic
                }
                return@forEachIndexed
            }
            pary.add(Para(wpis, nowy, docelowa))
        }
        return pary
    }

    /** Krok 2: jedno systemowe pytanie o przeniesienie oryginalow do kosza. */
    fun zadanieKosza(ctx: Context, oryginaly: List<Uri>): PendingIntent =
        MediaStore.createTrashRequest(ctx.contentResolver, oryginaly, true)

    /** Krok 3: nadanie docelowej nazwy i odslonięcie pliku w galerii. */
    fun dokoncz(ctx: Context, pary: List<Para>, baza: Baza): Int {
        var zrobione = 0
        for (para in pary) {
            val dane = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, para.docelowaNazwa)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val ok = try {
                ctx.contentResolver.update(para.nowyUri, dane, null, null) > 0
            } catch (e: Exception) {
                // Nazwa zajeta - probujemy z dopiskiem
                val zapas = ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        para.docelowaNazwa.replace(".mp4", "_opt.mp4")
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                try {
                    ctx.contentResolver.update(para.nowyUri, zapas, null, null) > 0
                } catch (e2: Exception) {
                    false
                }
            }
            if (!ok) continue

            ustawDateModyfikacji(ctx, para.nowyUri, para.wpis.dataModyfikacji)
            File(para.wpis.plikWyniku).delete()
            baza.ustawStatus(
                para.wpis.klucz, "podmieniony",
                "oryginał w koszu systemowym", para.nowyUri.toString()
            )
            zrobione++
        }
        return zrobione
    }

    /**
     * Data pliku przepisana z oryginalu. Bez tego galeria ustawia filmy
     * w kolejnosci wedlug dzisiejszej daty i archiwum sie rozjezdza.
     */
    private fun ustawDateModyfikacji(ctx: Context, uri: Uri, dataMs: Long) {
        if (dataMs <= 0) return
        try {
            ctx.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
            )?.use { k ->
                if (k.moveToFirst()) {
                    val sciezka = k.getString(0)
                    if (!sciezka.isNullOrBlank()) File(sciezka).setLastModified(dataMs)
                }
            }
        } catch (e: Exception) {
            // nieistotne dla poprawnosci pliku
        }
    }

    /** Kosz: przywrocenie oryginalow. */
    fun zadaniePrzywrocenia(ctx: Context, uris: List<Uri>): PendingIntent =
        MediaStore.createTrashRequest(ctx.contentResolver, uris, false)

    /** Kosz: trwale skasowanie - dopiero to zwalnia miejsce. */
    fun zadanieKasowania(ctx: Context, uris: List<Uri>): PendingIntent =
        MediaStore.createDeleteRequest(ctx.contentResolver, uris)
}
