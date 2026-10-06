package pl.mediaslim

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore

/** Co znalazl skan: kandydaci plus powody pominiec. */
data class WynikSkanu(
    val kandydaci: List<Film>,
    val obejrzane: Int,
    val pominiete: Map<String, Int>
) {
    val doOdzyskania: Long get() = kandydaci.sumOf { it.zysk }
    val razem: Long get() = kandydaci.sumOf { it.rozmiar }
}

object Skaner {

    /** Pliki poza tymi folderami zostawiamy w spokoju. */
    private val DOZWOLONE_FOLDERY = listOf("DCIM/", "Movies/", "Pictures/")

    /**
     * Zdjecia przerabiamy tylko z formatow stratnych. PNG i WEBP to zwykle
     * zrzuty ekranu albo grafiki z przezroczystoscia - przepisanie ich na
     * JPEG rozmywa tekst i gubi kanal alfa, a czesto wychodzi wieksze.
     */
    private val FORMATY_ZDJEC = setOf(
        "image/jpeg", "image/jpg", "image/heic", "image/heif"
    )

    // =================================================================
    //  Wideo
    // =================================================================

    fun skanuj(
        ctx: Context,
        baza: Baza,
        profil: Profil,
        postep: (Int, String) -> Unit,
        przerwane: () -> Boolean
    ): WynikSkanu {

        val znane = baza.znane()
        val kandydaci = ArrayList<Film>()
        val pominiete = LinkedHashMap<String, Int>()
        fun pomin(powod: String) {
            pominiete[powod] = (pominiete[powod] ?: 0) + 1
        }

        var obejrzane = 0
        val kolumny = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            kolumny, null, null,
            MediaStore.Video.Media.SIZE + " DESC"
        )?.use { k ->
            val kId = k.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val kNazwa = k.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val kRozmiar = k.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val kCzas = k.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val kSciezka = k.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val kData = k.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val kMod = k.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

            while (k.moveToNext()) {
                if (przerwane()) break
                obejrzane++

                val id = k.getLong(kId)
                val nazwa = k.getString(kNazwa) ?: continue
                val rozmiar = k.getLong(kRozmiar)
                val czasMs = k.getLong(kCzas)
                val wzgledna = k.getString(kSciezka) ?: ""
                val dataZrobienia = k.getLong(kData)
                val dataModyfikacji = k.getLong(kMod) * 1000L

                postep(obejrzane, nazwa)

                if (rozmiar < profil.minMB * 1024L * 1024L) {
                    pomin("za małe"); continue
                }
                // Android/media/... nalezy do innych aplikacji (WhatsApp, Messenger)
                // - nie wolno tam pisac, wiec nie ma po co kodowac.
                if (DOZWOLONE_FOLDERY.none { wzgledna.startsWith(it) }) {
                    pomin("poza DCIM/Movies/Pictures"); continue
                }

                val klucz = kluczPliku(id, rozmiar, dataModyfikacji)
                if (znane.contains(klucz)) {
                    pomin("znane z poprzedniego razu"); continue
                }

                val uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                )
                val p = parametry(ctx, uri)
                if (p == null) {
                    pomin("nie da się odczytać"); continue
                }

                if (p.kodek.contains("hevc") || p.kodek.contains("av01") ||
                    p.kodek.contains("dolby-vision")
                ) {
                    baza.zapisz(
                        klucz, uri, nazwa, rozmiar, czasMs, wzgledna, dataZrobienia,
                        dataModyfikacji, p.szerokosc, p.wysokosc, p.obrot, p.maAudio,
                        "pominiety", "już w nowoczesnym formacie (${p.kodek})"
                    )
                    znane.add(klucz)
                    pomin("już HEVC/AV1"); continue
                }
                if (p.hdr) {
                    baza.zapisz(
                        klucz, uri, nazwa, rozmiar, czasMs, wzgledna, dataZrobienia,
                        dataModyfikacji, p.szerokosc, p.wysokosc, p.obrot, p.maAudio,
                        "pominiety", "nagranie HDR - w tej wersji nie ruszam"
                    )
                    znane.add(klucz)
                    pomin("HDR"); continue
                }
                if (p.fps > 61f) {
                    baza.zapisz(
                        klucz, uri, nazwa, rozmiar, czasMs, wzgledna, dataZrobienia,
                        dataModyfikacji, p.szerokosc, p.wysokosc, p.obrot, p.maAudio,
                        "pominiety", "zwolnione tempo (${Math.round(p.fps)} kl/s)"
                    )
                    znane.add(klucz)
                    pomin("zwolnione tempo"); continue
                }

                val dluzszy = maxOf(p.szerokosc, p.wysokosc)
                val krotszy = minOf(p.szerokosc, p.wysokosc)
                if (krotszy < 2 || czasMs < 1000) {
                    pomin("nie da się odczytać"); continue
                }

                // Ramka kwadratowa: ograniczamy DLUZSZY bok, niezaleznie od tego,
                // czy film jest poziomy czy pionowy. Krotszy bok skaluje sie sam.
                var celKrotszy = 0
                var docelowyDluzszy = dluzszy
                var docelowyKrotszy = krotszy
                if (dluzszy > profil.maxDluzszyBok) {
                    val skala = profil.maxDluzszyBok.toDouble() / dluzszy
                    celKrotszy = (Math.round(krotszy * skala / 2.0) * 2).toInt()
                    docelowyDluzszy = profil.maxDluzszyBok
                    docelowyKrotszy = celKrotszy
                }
                if (docelowyKrotszy < 240) {
                    pomin("skrajne proporcje"); continue
                }

                val sekundy = czasMs / 1000.0
                val mbps = if (sekundy > 0) rozmiar * 8.0 / sekundy / 1_000_000.0 else 0.0
                val prog = profil.bppProgu * docelowyDluzszy * docelowyKrotszy *
                    p.fps / 1_000_000.0

                if (mbps > 0 && mbps < prog) {
                    pomin("już oszczędny bitrate"); continue
                }

                val bitrate = (profil.bppKodowania * docelowyDluzszy * docelowyKrotszy *
                    p.fps).toInt().coerceAtLeast(500_000)
                val szacowany = ((bitrate + if (p.maAudio) 160_000 else 0) *
                    sekundy / 8.0).toLong()

                kandydaci.add(
                    Film(
                        id = id, uri = uri, nazwa = nazwa, rozmiar = rozmiar,
                        czasMs = czasMs, sciezkaWzgledna = wzgledna,
                        dataZrobienia = dataZrobienia, dataModyfikacji = dataModyfikacji,
                        szerokosc = p.szerokosc, wysokosc = p.wysokosc, obrot = p.obrot,
                        fps = p.fps, kodek = p.kodek, maAudio = p.maAudio, mbps = mbps,
                        docelowyKrotszyBok = celKrotszy, bitrateDocelowy = bitrate,
                        szacowanyRozmiar = szacowany
                    )
                )
            }
        }

        return WynikSkanu(kandydaci, obejrzane, pominiete)
    }

    // =================================================================
    //  Zdjecia
    // =================================================================

    /**
     * Ile bajtow zajmie piksel po zapisie JPEG. Wartosc wzieta z archiwum
     * na komputerze: 12-megapikselowe zdjecia z telefonu zmniejszane do
     * 2500 px wychodzily na 0,26-0,72 bajta na piksel. Sluzy wylacznie do
     * pokazania szacowanego zysku przed kodowaniem.
     */
    private const val BAJTY_NA_PIKSEL = 0.40

    fun skanujZdjecia(
        ctx: Context,
        baza: Baza,
        profil: Profil,
        postep: (Int, String) -> Unit,
        przerwane: () -> Boolean
    ): WynikSkanu {

        val znane = baza.znane()
        val kandydaci = ArrayList<Film>()
        val pominiete = LinkedHashMap<String, Int>()
        fun pomin(powod: String) {
            pominiete[powod] = (pominiete[powod] ?: 0) + 1
        }

        var obejrzane = 0
        val kolumny = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.MIME_TYPE
        )

        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            kolumny, null, null,
            MediaStore.Images.Media.SIZE + " DESC"
        )?.use { k ->
            val kId = k.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val kNazwa = k.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val kRozmiar = k.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val kSciezka = k.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val kData = k.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val kMod = k.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val kSzer = k.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val kWys = k.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val kObrot = k.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val kMime = k.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (k.moveToNext()) {
                if (przerwane()) break
                obejrzane++

                val id = k.getLong(kId)
                val nazwa = k.getString(kNazwa) ?: continue
                val rozmiar = k.getLong(kRozmiar)
                val wzgledna = k.getString(kSciezka) ?: ""
                val dataZrobienia = k.getLong(kData)
                val dataModyfikacji = k.getLong(kMod) * 1000L
                val szerPliku = k.getInt(kSzer)
                val wysPliku = k.getInt(kWys)
                val obrot = ((k.getInt(kObrot) % 360) + 360) % 360
                val mime = (k.getString(kMime) ?: "").lowercase()

                postep(obejrzane, nazwa)

                if (rozmiar < profil.minMBZdjecia * 1024L * 1024L) {
                    pomin("za małe"); continue
                }
                if (DOZWOLONE_FOLDERY.none { wzgledna.startsWith(it) }) {
                    pomin("poza DCIM/Movies/Pictures"); continue
                }
                if (!FORMATY_ZDJEC.contains(mime)) {
                    pomin("nie JPEG ani HEIC"); continue
                }
                if (szerPliku < 2 || wysPliku < 2) {
                    pomin("nie da się odczytać"); continue
                }

                val klucz = kluczPliku(id, rozmiar, dataModyfikacji)
                if (znane.contains(klucz)) {
                    pomin("znane z poprzedniego razu"); continue
                }

                // Wymiary liczymy tak, jak pokaze je galeria - po obrocie.
                // Obrot bedzie wypalony w pikselach, wiec ramke stosujemy
                // do tego, co uzytkownik naprawde widzi.
                val pokSzer = if (obrot == 90 || obrot == 270) wysPliku else szerPliku
                val pokWys = if (obrot == 90 || obrot == 270) szerPliku else wysPliku

                val cel = docelowePikseleZdjecia(pokSzer, pokWys, profil.maxBokZdjecia)
                if (cel == null) {
                    pomin("już mieści się w ramce"); continue
                }

                val szacowany = (cel.first.toDouble() * cel.second * BAJTY_NA_PIKSEL).toLong()
                if (szacowany >= rozmiar * 0.9) {
                    pomin("zysk zbyt mały"); continue
                }

                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )

                kandydaci.add(
                    Film(
                        id = id, uri = uri, nazwa = nazwa, rozmiar = rozmiar,
                        czasMs = 0L, sciezkaWzgledna = wzgledna,
                        dataZrobienia = dataZrobienia, dataModyfikacji = dataModyfikacji,
                        szerokosc = szerPliku, wysokosc = wysPliku, obrot = obrot,
                        fps = 0f, kodek = mime, maAudio = false, mbps = 0.0,
                        docelowyKrotszyBok = 0, bitrateDocelowy = 0,
                        szacowanyRozmiar = szacowany,
                        czyZdjecie = true,
                        docelowaSzer = cel.first, docelowaWys = cel.second,
                        jakosc = profil.jakoscZdjecia
                    )
                )
            }
        }

        return WynikSkanu(kandydaci, obejrzane, pominiete)
    }

    // =================================================================

    data class Parametry(
        val szerokosc: Int, val wysokosc: Int, val obrot: Int,
        val fps: Float, val kodek: String, val maAudio: Boolean, val hdr: Boolean
    )

    /** Czyta naglowek pliku: rozdzielczosc, kodek, klatkaz, obrot, dzwiek. */
    fun parametry(ctx: Context, uri: Uri): Parametry? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var wideo: MediaFormat? = null
            var maAudio = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && wideo == null) wideo = f
                if (mime.startsWith("audio/")) maAudio = true
            }
            val f = wideo ?: return null
            val szer = liczba(f, MediaFormat.KEY_WIDTH, 0)
            val wys = liczba(f, MediaFormat.KEY_HEIGHT, 0)
            if (szer <= 0 || wys <= 0) return null
            val obrot = liczba(f, MediaFormat.KEY_ROTATION, 0)
            val transfer = liczba(f, MediaFormat.KEY_COLOR_TRANSFER, 0)
            val hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                transfer == MediaFormat.COLOR_TRANSFER_HLG
            var fps = 30f
            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                // Raz bywa liczba calkowita, raz zmiennoprzecinkowa.
                fps = try {
                    f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                } catch (e: ClassCastException) {
                    f.getFloat(MediaFormat.KEY_FRAME_RATE)
                }
            }
            if (fps <= 0f || fps > 480f) fps = 30f
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            return Parametry(szer, wys, obrot, fps, mime.removePrefix("video/"), maAudio, hdr)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                ex.release()
            } catch (e: Exception) {
                // nic
            }
        }
    }

    private fun liczba(f: MediaFormat, klucz: String, domyslna: Int): Int =
        if (f.containsKey(klucz)) {
            try {
                f.getInteger(klucz)
            } catch (e: Exception) {
                domyslna
            }
        } else domyslna
}
