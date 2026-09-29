package pl.mediaslim

import android.content.Context

/**
 * Profil pracy. Progi przeniesione z wersji na Windows, gdzie zostaly
 * zmierzone na archiwum 1459 filmow.
 *
 * bppKodowania - ile bitow na piksel na klatke dostaje koder
 * bppProgu     - ponizej tego plik jest juz oszczedny i go nie ruszamy;
 *                musi byc wyzszy od bppKodowania, inaczej wynik wyszedlby
 *                wiekszy od oryginalu
 */
data class Profil(
    val id: String,
    val nazwa: String,
    val opis: String,
    val maxDluzszyBok: Int,
    val bppKodowania: Double,
    val bppProgu: Double,
    val minMB: Int
)

object Ustawienia {

    val PROFILE = listOf(
        Profil(
            "ostrozny", "Ostrożny",
            "Tylko duże filmy, najwyższa jakość. Mały zysk, zero ryzyka.",
            1920, 0.075, 0.10, 50
        ),
        Profil(
            "zrownowazony", "Zrównoważony",
            "Ustawienia sprawdzone na archiwum 1459 filmów. Zalecany.",
            1920, 0.060, 0.08, 20
        ),
        Profil(
            "agresywny", "Maksymalny zysk",
            "Mniejsze pliki, strata widoczna na dużym ekranie.",
            1280, 0.045, 0.06, 10
        )
    )

    private const val PLIK = "mediaslim"
    private const val KLUCZ_PROFIL = "profil"

    fun profil(ctx: Context): Profil {
        val id = ctx.getSharedPreferences(PLIK, Context.MODE_PRIVATE)
            .getString(KLUCZ_PROFIL, "zrownowazony")
        return PROFILE.firstOrNull { it.id == id } ?: PROFILE[1]
    }

    fun ustawProfil(ctx: Context, id: String) {
        ctx.getSharedPreferences(PLIK, Context.MODE_PRIVATE)
            .edit()
            .putString(KLUCZ_PROFIL, id)
            .apply()
    }
}
