package pl.mediaslim

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

/**
 * Pamiec aplikacji: co juz obejrzane, co zakodowane, co podmienione.
 *
 * Klucz to numer pliku w galerii razem z jego rozmiarem i data zmiany.
 * Dzieki temu plik podmieniony albo edytowany gdzie indziej dostaje nowy
 * klucz i zostanie sprawdzony jeszcze raz, a nietkniety - pominiety.
 */
class Baza(ctx: Context) : SQLiteOpenHelper(ctx, "mediaslim.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE pliki(
              klucz TEXT PRIMARY KEY,
              uri TEXT,
              nazwa TEXT,
              rozmiar INTEGER,
              czas_ms INTEGER,
              sciezka_wzgledna TEXT,
              data_zrobienia INTEGER,
              data_modyfikacji INTEGER,
              szerokosc INTEGER,
              wysokosc INTEGER,
              obrot INTEGER,
              ma_audio INTEGER,
              status TEXT,
              powod TEXT,
              plik_wyniku TEXT,
              rozmiar_wyniku INTEGER,
              nowy_uri TEXT,
              kiedy INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX i_status ON pliki(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, stara: Int, nowa: Int) {
        // Pierwsza wersja schematu - nie ma z czego migrowac.
    }

    fun zapisz(
        klucz: String, uri: Uri, nazwa: String, rozmiar: Long, czasMs: Long,
        sciezkaWzgledna: String, dataZrobienia: Long, dataModyfikacji: Long,
        szerokosc: Int, wysokosc: Int, obrot: Int, maAudio: Boolean,
        status: String, powod: String,
        plikWyniku: String? = null, rozmiarWyniku: Long = 0, nowyUri: String? = null
    ) {
        val w = ContentValues().apply {
            put("klucz", klucz)
            put("uri", uri.toString())
            put("nazwa", nazwa)
            put("rozmiar", rozmiar)
            put("czas_ms", czasMs)
            put("sciezka_wzgledna", sciezkaWzgledna)
            put("data_zrobienia", dataZrobienia)
            put("data_modyfikacji", dataModyfikacji)
            put("szerokosc", szerokosc)
            put("wysokosc", wysokosc)
            put("obrot", obrot)
            put("ma_audio", if (maAudio) 1 else 0)
            put("status", status)
            put("powod", powod)
            put("plik_wyniku", plikWyniku)
            put("rozmiar_wyniku", rozmiarWyniku)
            put("nowy_uri", nowyUri)
            put("kiedy", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "pliki", null, w, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Klucze, ktorych nie trzeba ponownie badac przy skanowaniu. */
    fun znane(): MutableSet<String> {
        val zbior = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT klucz FROM pliki WHERE status <> 'nowy'", null
        ).use { k ->
            while (k.moveToNext()) zbior.add(k.getString(0))
        }
        return zbior
    }

    fun oStatusie(status: String): List<Gotowy> {
        val lista = ArrayList<Gotowy>()
        readableDatabase.rawQuery(
            "SELECT klucz,uri,nazwa,rozmiar,rozmiar_wyniku,plik_wyniku," +
                "sciezka_wzgledna,czas_ms,ma_audio,szerokosc,wysokosc,obrot," +
                "data_zrobienia,data_modyfikacji,status,powod " +
                "FROM pliki WHERE status = ? ORDER BY rozmiar DESC", arrayOf(status)
        ).use { k ->
            while (k.moveToNext()) {
                lista.add(
                    Gotowy(
                        klucz = k.getString(0),
                        uri = Uri.parse(k.getString(1)),
                        nazwa = k.getString(2) ?: "",
                        rozmiar = k.getLong(3),
                        rozmiarWyniku = k.getLong(4),
                        plikWyniku = k.getString(5) ?: "",
                        sciezkaWzgledna = k.getString(6) ?: "",
                        czasMs = k.getLong(7),
                        maAudio = k.getInt(8) == 1,
                        szerokosc = k.getInt(9),
                        wysokosc = k.getInt(10),
                        obrot = k.getInt(11),
                        dataZrobienia = k.getLong(12),
                        dataModyfikacji = k.getLong(13),
                        status = k.getString(14) ?: "",
                        powod = k.getString(15) ?: ""
                    )
                )
            }
        }
        return lista
    }

    fun wKoszu(): List<WKoszu> {
        val lista = ArrayList<WKoszu>()
        readableDatabase.rawQuery(
            "SELECT klucz,uri,nazwa,rozmiar FROM pliki " +
                "WHERE status = 'podmieniony' ORDER BY rozmiar DESC", null
        ).use { k ->
            while (k.moveToNext()) {
                lista.add(
                    WKoszu(k.getString(0), Uri.parse(k.getString(1)),
                        k.getString(2) ?: "", k.getLong(3))
                )
            }
        }
        return lista
    }

    fun ustawStatus(klucz: String, status: String, powod: String, nowyUri: String? = null) {
        val w = ContentValues().apply {
            put("status", status)
            put("powod", powod)
            if (nowyUri != null) put("nowy_uri", nowyUri)
            put("kiedy", System.currentTimeMillis())
        }
        writableDatabase.update("pliki", w, "klucz = ?", arrayOf(klucz))
    }

    fun usun(klucz: String) {
        writableDatabase.delete("pliki", "klucz = ?", arrayOf(klucz))
    }

    /** Liczniki do ekranu glownego: status -> ile sztuk i ile bajtow zysku. */
    fun podsumowanie(): Map<String, Pair<Int, Long>> {
        val mapa = LinkedHashMap<String, Pair<Int, Long>>()
        readableDatabase.rawQuery(
            "SELECT status, COUNT(*), " +
                "SUM(CASE WHEN rozmiar_wyniku > 0 THEN rozmiar - rozmiar_wyniku ELSE 0 END) " +
                "FROM pliki GROUP BY status", null
        ).use { k ->
            while (k.moveToNext()) {
                mapa[k.getString(0) ?: "?"] = Pair(k.getInt(1), k.getLong(2))
            }
        }
        return mapa
    }
}
