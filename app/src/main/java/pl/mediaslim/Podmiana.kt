package pl.mediaslim

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
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

    /**
     * Gotowe zadanie dla systemu albo powod, dla ktorego go nie ma.
     *
     * `martwe` to adresy, ktorych juz nie ma w galerii - trzeba je usunac
     * z naszej bazy, bo inaczej beda wracac przy kazdej probie.
     */
    data class Zadanie(
        val intent: PendingIntent?,
        val objete: List<Uri>,
        val martwe: List<Uri>,
        val blad: String?
    )

    private fun kolekcja(czyZdjecie: Boolean): Uri =
        if (czyZdjecie) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * Odswieza adres: sprawdza, czy wpis nadal istnieje, i odtwarza adres
     * na KONKRETNYM wolumenie.
     *
     * Dwa powody, oba wyjasniaja wywrotke "Invalid Uri" przy oproznianiu kosza:
     *
     * 1. Adres zapisany w naszej bazie wskazuje wolumen "external", ktory
     *    jest nazwa zbiorcza, a nie prawdziwym nosnikiem. Czesc dostawcow
     *    MediaStore (w tym ten z HyperOS) odrzuca taki adres przy zadaniu
     *    kasowania. Czytamy wiec VOLUME_NAME z samego wpisu i skladamy
     *    adres np. na "external_primary".
     *
     * 2. Wpisu moze juz nie byc - uzytkownik oproznil kosz w Galerii albo
     *    system przeindeksowal plik i nadal mu nowy numer. MediaStore nie
     *    zwraca wtedy bledu, tylko RZUCA wyjatkiem prosto z dostawcy, co
     *    konczy sie wywaleniem aplikacji.
     *
     * Zapytanie musi obejmowac kosz (QUERY_ARG_MATCH_TRASHED), bo zwykle
     * zapytanie nie widzi plikow wyrzuconych do kosza - czyli dokladnie
     * tych, o ktore nam tu chodzi.
     */
    private fun odswiezAdres(ctx: Context, uri: Uri, czyZdjecie: Boolean): Uri? {
        val id = try {
            ContentUris.parseId(uri)
        } catch (e: Exception) {
            return null
        }
        if (id <= 0) return null

        val argumenty = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns._ID + " = " + id
            )
        }
        val gdzieSzukac = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val kolumny = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.VOLUME_NAME
        )
        return try {
            ctx.contentResolver.query(gdzieSzukac, kolumny, argumenty, null)?.use { k ->
                if (!k.moveToFirst()) return null
                val wolumen = k.getString(1) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                if (czyZdjecie) {
                    ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(wolumen), id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(wolumen), id)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Dzieli liste na te, ktore nadal istnieja, i te, ktorych juz nie ma. */
    private fun rozdziel(
        ctx: Context,
        pozycje: List<Pair<Uri, Boolean>>
    ): Pair<MutableList<Uri>, MutableList<Uri>> {
        val zywe = ArrayList<Uri>()
        val martwe = ArrayList<Uri>()
        for ((uri, czyZdjecie) in pozycje) {
            val swiezy = odswiezAdres(ctx, uri, czyZdjecie)
            if (swiezy != null) zywe.add(swiezy) else martwe.add(uri)
        }
        return Pair(zywe, martwe)
    }

    /**
     * Opakowanie na systemowe zadanie. MediaStore.create*Request potrafi
     * rzucic wyjatkiem zamiast zwrocic blad - bez tego opakowania jeden
     * nieaktualny wpis wywala cala aplikacje.
     */
    private fun zbuduj(
        zywe: List<Uri>,
        martwe: List<Uri>,
        budowniczy: (List<Uri>) -> PendingIntent
    ): Zadanie {
        if (zywe.isEmpty()) {
            return Zadanie(null, emptyList(), martwe,
                if (martwe.isEmpty()) "nie ma czego przetworzyć"
                else "tych plików nie ma już w galerii")
        }
        return try {
            Zadanie(budowniczy(zywe), zywe, martwe, null)
        } catch (e: Exception) {
            Zadanie(null, emptyList(), martwe,
                e.message ?: e.javaClass.simpleName)
        }
    }

    /** Krok 1: wrzuca gotowe pliki do galerii jako ukryte (pending). */
    fun przygotuj(ctx: Context, wpisy: List<Gotowy>, postep: (Int, String) -> Unit): List<Para> {
        val pary = ArrayList<Para>()
        wpisy.forEachIndexed { indeks, wpis ->
            postep(indeks + 1, wpis.nazwa)
            val plik = File(wpis.plikWyniku)
            if (!plik.exists()) return@forEachIndexed

            val rdzen = wpis.nazwa.substringBeforeLast('.', wpis.nazwa)
            val tymczasowa = "$rdzen.mediaslim-tmp.${wpis.rozszerzenie}"
            val docelowa = "$rdzen.${wpis.rozszerzenie}"

            val dane = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, tymczasowa)
                put(MediaStore.MediaColumns.MIME_TYPE, wpis.typMime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, wpis.sciezkaWzgledna)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (wpis.dataZrobienia > 0) {
                    put(MediaStore.MediaColumns.DATE_TAKEN, wpis.dataZrobienia)
                }
            }

            val nowy = try {
                ctx.contentResolver.insert(kolekcja(wpis.czyZdjecie), dane)
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
    fun zadanieKosza(ctx: Context, pary: List<Para>): Zadanie {
        val (zywe, martwe) = rozdziel(ctx, pary.map { Pair(it.wpis.uri, it.wpis.czyZdjecie) })
        return zbuduj(zywe, martwe) {
            MediaStore.createTrashRequest(ctx.contentResolver, it, true)
        }
    }

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
                val kropka = para.docelowaNazwa.lastIndexOf('.')
                val zapasowa =
                    if (kropka > 0) para.docelowaNazwa.substring(0, kropka) + "_opt" +
                        para.docelowaNazwa.substring(kropka)
                    else para.docelowaNazwa + "_opt"
                val zapas = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, zapasowa)
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
     * Data pliku przepisana z oryginalu. Bez tego galeria ustawia pliki
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
    fun zadaniePrzywrocenia(ctx: Context, pozycje: List<WKoszu>): Zadanie {
        val (zywe, martwe) = rozdziel(ctx, pozycje.map { Pair(it.uri, it.czyZdjecie) })
        return zbuduj(zywe, martwe) {
            MediaStore.createTrashRequest(ctx.contentResolver, it, false)
        }
    }

    /** Kosz: trwale skasowanie - dopiero to zwalnia miejsce. */
    fun zadanieKasowania(ctx: Context, pozycje: List<WKoszu>): Zadanie {
        val (zywe, martwe) = rozdziel(ctx, pozycje.map { Pair(it.uri, it.czyZdjecie) })
        return zbuduj(zywe, martwe) {
            MediaStore.createDeleteRequest(ctx.contentResolver, it)
        }
    }

    /**
     * Wpisy, ktorych nie ma juz w galerii, wypadaja z naszej bazy.
     * Inaczej wracalyby w koszu przy kazdym otwarciu aplikacji.
     */
    fun zapomnijMartwe(baza: Baza, pozycje: List<WKoszu>, martwe: List<Uri>) {
        if (martwe.isEmpty()) return
        val zbior = martwe.map { it.toString() }.toSet()
        for (p in pozycje) {
            if (zbior.contains(p.uri.toString())) baza.usun(p.klucz)
        }
    }
}
