# MediaSlim na Androida (wersja 0.1, tylko wideo)

Zmniejsza filmy w galerii telefonu bez utraty jakości widocznej dla oka
i bez ryzyka utraty materiału.

## Co robi

1. **Skanuje** filmy w DCIM / Movies / Pictures i pokazuje, ile da się odzyskać.
2. **Pomija to, co nie ma sensu**: nagrania już w HEVC/AV1, filmy o oszczędnym
   bitrate, HDR, zwolnione tempo, pliki poniżej progu i cudze foldery aplikacji.
3. **Koduje** do HEVC koderem sprzętowym telefonu, w usłudze działającej przy
   zgaszonym ekranie. Dłuższy bok ograniczany do 1920 px (ramka kwadratowa, więc
   film pionowy nie jest duszony).
4. **Sprawdza wynik** pięcioma testami: mniejszy, ta sama długość, jest dźwięk,
   nie leży na boku, zachowana data nagrania. Plik, który nie przejdzie, nie
   zostanie podmieniony.
5. **Podmienia** w trzech krokach — kopia trafia do galerii jako ukryta, oryginał
   idzie do kosza systemowego (jedno pytanie na całą paczkę), na końcu kopia
   dostaje docelową nazwę. W żadnym momencie nie ma stanu bez kompletnej kopii.
6. **Kosz** — oryginały są odzyskiwalne przez 30 dni. Miejsce zwalnia się dopiero
   po opróżnieniu kosza w aplikacji.

Zatrzymanie pracy w dwóch trybach: „po bieżącym pliku" i „przerwij teraz"
(niedokończony plik jest kasowany i przy następnym uruchomieniu robiony od nowa).

## Jak zbudować APK

Nic nie musisz instalować. Po wrzuceniu tego katalogu do repozytorium na GitHubie
budowanie rusza samo: zakładka **Actions** → po kilku minutach **Releases** →
`build-N` → plik `MediaSlim-0.1.N.apk`. Otwórz tę stronę na telefonie i pobierz.

## Klucz podpisu

`klucz/mediaslim.jks.b64` to klucz testowy trzymany jako tekst; workflow rozpakowuje
go przed budowaniem. Dzięki temu każda kolejna wersja instaluje się na poprzedniej.
Nie nadaje się do publikacji w Google Play — tam potrzebny jest osobny klucz
trzymany w sekretach repozytorium.

## Czego wersja 0.1 nie robi

Zdjęć (są w wersji na Windows), duplikatów, ustawień poza trzema profilami,
plików spoza galerii.
