package pl.mediaslim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Zmniejszanie zdjec.
 *
 * Trzy rzeczy, na ktorych wywrocila sie wersja na komputer i ktore tu sa
 * zrobione od razu poprawnie:
 *
 *  1. Ramka jest kwadratowa (patrz docelowePikseleZdjecia) - zdjecie
 *     pionowe nie jest duszone do proporcji poziomej.
 *  2. Obrot z EXIF jest WYPALANY w pikselach, a znacznik orientacji
 *     w wyniku ustawiamy na "normalny". Gdyby zostal stary znacznik,
 *     galeria obrocilaby zdjecie drugi raz i kazda pionowa fotka
 *     lezalaby na boku.
 *  3. Data zrobienia, aparat i GPS sa przepisywane do wyniku. Bez tego
 *     cale archiwum przeskakuje na gore osi czasu z dzisiejsza data.
 *
 * Korzystamy z systemowego ExifInterface (jest od API 24), zeby nie
 * dokladac zaleznosci do aplikacji.
 */
object KodowanieZdjec {

    /**
     * Znaczniki przepisywane z oryginalu. Orientacji tu NIE MA celowo -
     * ustawiamy ja osobno na "normalna", bo obrot jest juz w pikselach.
     */
    private val ZNACZNIKI = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    fun zakoduj(
        ctx: Context,
        film: Film,
        plikWyjsciowy: File,
        postep: (Int) -> Unit
    ): RezultatKodowania {
        if (film.docelowaSzer < 2 || film.docelowaWys < 2) {
            return RezultatKodowania.Blad("brak docelowych wymiarów")
        }

        // Cel liczony PRZED obrotem: docelowe wymiary sa podane tak, jak
        // pokaze je galeria, a obracamy dopiero po przeskalowaniu - taniej.
        val obrocony = film.obrot == 90 || film.obrot == 270
        val celSzer = if (obrocony) film.docelowaWys else film.docelowaSzer
        val celWys = if (obrocony) film.docelowaSzer else film.docelowaWys

        var zrodlowy: Bitmap? = null
        var przeskalowany: Bitmap? = null
        var gotowy: Bitmap? = null
        try {
            postep(5)

            val opcje = BitmapFactory.Options().apply {
                inSampleSize = probkowanie(film.szerokosc, film.wysokosc, celSzer, celWys)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            zrodlowy = ctx.contentResolver.openInputStream(film.uri).use { we ->
                if (we == null) null else BitmapFactory.decodeStream(we, null, opcje)
            } ?: return RezultatKodowania.Blad("nie da się odczytać zdjęcia")

            postep(40)

            przeskalowany =
                if (zrodlowy.width == celSzer && zrodlowy.height == celWys) zrodlowy
                else Bitmap.createScaledBitmap(zrodlowy, celSzer, celWys, true)

            postep(65)

            gotowy = if (film.obrot != 0) {
                val m = Matrix().apply { postRotate(film.obrot.toFloat()) }
                Bitmap.createBitmap(
                    przeskalowany, 0, 0, przeskalowany.width, przeskalowany.height, m, true
                )
            } else {
                przeskalowany
            }

            postep(80)

            FileOutputStream(plikWyjsciowy).use { wy ->
                if (!gotowy.compress(Bitmap.CompressFormat.JPEG, film.jakosc, wy)) {
                    return RezultatKodowania.Blad("zapis JPEG nie powiódł się")
                }
            }

            postep(92)
            przepiszZnaczniki(ctx, film, plikWyjsciowy)
            postep(100)
        } catch (e: OutOfMemoryError) {
            if (plikWyjsciowy.exists()) plikWyjsciowy.delete()
            return RezultatKodowania.Blad("za mało pamięci na to zdjęcie")
        } catch (e: Exception) {
            if (plikWyjsciowy.exists()) plikWyjsciowy.delete()
            return RezultatKodowania.Blad(e.message ?: e.javaClass.simpleName)
        } finally {
            // Kazdy z trzech moze byc tym samym obiektem - recycle tylko raz.
            if (gotowy !== przeskalowany) gotowy?.recycle()
            if (przeskalowany !== zrodlowy) przeskalowany?.recycle()
            zrodlowy?.recycle()
        }

        val rozmiar = if (plikWyjsciowy.exists()) plikWyjsciowy.length() else 0L
        return if (rozmiar > 0) RezultatKodowania.Udane(plikWyjsciowy, rozmiar)
        else RezultatKodowania.Blad("pusty plik wynikowy")
    }

    /**
     * Najwieksze probkowanie, przy ktorym obraz jest nadal co najmniej
     * tak duzy jak cel. Dekodowanie 50-megapikselowego zdjecia w pelnej
     * rozdzielczosci to 200 MB pamieci i pewna wywrotka.
     */
    private fun probkowanie(szer: Int, wys: Int, celSzer: Int, celWys: Int): Int {
        var p = 1
        while (szer / (p * 2) >= celSzer && wys / (p * 2) >= celWys) p *= 2
        return p
    }

    private fun przepiszZnaczniki(ctx: Context, film: Film, plik: File) {
        try {
            val zrodlo = ctx.contentResolver.openInputStream(film.uri).use { we ->
                if (we == null) null else ExifInterface(we)
            } ?: return
            val cel = ExifInterface(plik.absolutePath)
            for (z in ZNACZNIKI) {
                val wartosc = zrodlo.getAttribute(z)
                if (wartosc != null) cel.setAttribute(z, wartosc)
            }
            // Obrot jest juz w pikselach - znacznik musi mowic "nie obracaj".
            cel.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
            cel.saveAttributes()
        } catch (e: Exception) {
            // Zdjecie jest poprawne nawet bez znacznikow - weryfikacja
            // sprawdzi osobno, czy data nie zniknela.
        }
    }
}
