# MediaSlim Android 0.1 — od zera do aplikacji na telefonie

## Część 1. Wrzucenie projektu na GitHub (raz, ~7 minut)

1. Wejdź na **github.com** i zaloguj się jako **Kubollaz**.
2. Prawy górny róg → **+** → **New repository**.
   - Repository name: `mediaslim-android`
   - Zaznacz **Private**.
   - **Nie zaznaczaj** „Add a README file" ani niczego innego.
   - **Create repository**.
3. Na pustej stronie repozytorium kliknij link **uploading an existing file**.
4. Otwórz Eksplorator na `D:\MediaSlim\android`, zaznacz wszystko **wewnątrz**
   tego folderu (Ctrl+A) i przeciągnij na stronę GitHuba. Przeciągasz zawartość
   folderu `android`, a nie sam folder — inaczej wszystko wyląduje o poziom
   głębiej.
5. Na dole **Commit changes**.

### Folder `.github` — trzeba go zrobić na stronie

Windows traktuje foldery zaczynające się od kropki jako ukryte i przeglądarka
ich nie wysyła. `.github` nie da się wrzucić przeciąganiem — ani z Chrome, ani
z Edge, niezależnie od ustawień Eksploratora. Dlatego ten jeden plik tworzymy
bezpośrednio na GitHubie:

1. Na stronie repozytorium: **Add file** → **Create new file**.
2. W polu nazwy wpisz dokładnie:

   ```
   .github/workflows/buduj-apk.yml
   ```

   Ukośniki same zamienią się w foldery — po wpisaniu zobaczysz nad polem
   ścieżkę `mediaslim-android / .github / workflows / buduj-apk.yml`.
3. Otwórz `D:\MediaSlim\WORKFLOW-wklej-na-github.txt` w Notatniku,
   Ctrl+A, Ctrl+C i wklej w duże pole edytora na GitHubie.
4. **Commit changes** → zielony przycisk **Commit changes** w okienku.

Od tego commita budowanie rusza samo.

## Część 2. Budowanie APK (dzieje się samo)

1. Zakładka **Actions** — zobaczysz zadanie „Buduj APK" z kręcącym się kółkiem.
   Pierwsze budowanie trwa 6–10 minut (ściąga narzędzia), kolejne 2–3 minuty.
2. Zielony haczyk → zakładka **Releases** (prawa kolumna strony głównej
   repozytorium) → wpis **build-1** → plik `MediaSlim-0.1.1.apk`.
3. Czerwony krzyżyk → wejdź w to zadanie. Na samej górze, w ramce „Budowanie
   się nie udało", jest kilka linijek z błędem. Skopiuj je i wklej Claude.

Każda kolejna zmiana w repozytorium buduje nową wersję automatycznie.
Można też ręcznie: **Actions** → „Buduj APK" → **Run workflow**.

## Część 3. Instalacja na Xiaomi 14T (HyperOS)

Nazwy pozycji mogą się nieco różnić między wersjami HyperOS — jeśli nie widzisz
dokładnie takiej, szukaj hasła podanego pogrubieniem.

1. **Pobranie.** Na telefonie otwórz stronę repozytorium (Chrome), zakładka
   **Releases**, dotknij pliku `.apk`. Chrome ostrzeże, że „ten typ pliku może
   uszkodzić urządzenie" — **Pobierz mimo to**.
2. **Instalacja z nieznanego źródła.** Dotknij pobranego pliku. System powie, że
   ta aplikacja nie ma uprawnienia do instalowania. Dotknij **Ustawienia** i
   włącz przełącznik dla Chrome / Menedżera plików.
   Ręcznie: *Ustawienia → Hasła i zabezpieczenia → Prywatność → Uprawnienia
   specjalne → **Instalowanie nieznanych aplikacji***.
3. **Skanowanie Xiaomi.** HyperOS pokaże ekran „Skanowanie…" i może odmówić
   instalacji aplikacji spoza sklepu. Wtedy: *Ustawienia → Hasła i
   zabezpieczenia → Prywatność* → wyłącz **Skanuj urządzenie przed instalacją**
   (bywa też opisane jako „Zwiększone zabezpieczenia" / „Sprawdzaj aplikacje").
4. **Play Protect.** Jeśli Google pokaże „Aplikacja nieznanego autora":
   *Sklep Play → ikona profilu → Play Protect → koło zębate* → wyłącz
   **Skanuj aplikacje za pomocą Play Protect**. Po instalacji można włączyć
   z powrotem.
5. Zainstaluj i uruchom.

## Część 4. Ustawienia telefonu, żeby kodowanie nie padło przy zgaszonym ekranie

To jest ta część, którą naprawdę warto zrobić — HyperOS jest wyjątkowo
agresywny w ubijaniu aplikacji działających w tle.

1. **Dostęp do filmów.** Przy pierwszym uruchomieniu aplikacja poprosi o dostęp.
   Wybierz **Zezwalaj na wszystkie** (nie „Wybierz zdjęcia i filmy" — przy
   wybranych plikach nie da się przeszukać archiwum).
2. **Powiadomienia** — zezwól. W powiadomieniu jest postęp i dwa przyciski
   zatrzymania.
3. **Bateria bez ograniczeń.** *Ustawienia → Aplikacje → Zarządzaj aplikacjami
   → MediaSlim → Oszczędzanie baterii* → **Bez ograniczeń**.
4. **Autostart.** Na tym samym ekranie MediaSlim włącz **Autostart**.
5. **Blokada w ostatnich aplikacjach.** Otwórz listę ostatnich aplikacji
   (przeciągnięcie od dołu i przytrzymanie), przytrzymaj kafelek MediaSlim i
   wybierz **kłódkę**. Zablokowanej aplikacji system nie zamyka przy
   czyszczeniu pamięci.
6. *Ustawienia → Bateria* → wyłącz **Oszczędzanie baterii**, a w menu z trzema
   kropkami wyłącz **Zawieś aplikacje po zablokowaniu ekranu**, jeśli jest.
7. Podłącz ładowarkę. Kodowanie kilkudziesięciu filmów to godziny pracy
   procesora graficznego; telefon będzie ciepły i zjada baterię.
   Aplikacja sama robi przerwy, gdy telefon się przegrzeje.

## Część 5. Pierwsze uruchomienie — jak to sprawdzić bezpiecznie

1. Profil zostaw na **Zrównoważony**.
2. **Skanuj galerię**. Zobaczysz, ile filmów nadaje się do przerobienia i ile
   miejsca da się odzyskać.
3. Na początek **odznacz wszystkie i zaznacz 2–3 filmy** — w tym koniecznie
   jeden **nagrany pionowo** i jeden **z dźwiękiem**. To są dwie rzeczy, które
   najczęściej psuły się na wersji komputerowej.
4. **Koduj zaznaczone**. Możesz zgasić ekran.
5. Zakładka **Gotowe** → **Podmień**. Telefon zapyta raz o przeniesienie
   oryginałów do kosza — zgódź się.
6. **Obejrzyj te 2–3 filmy w galerii.** Sprawdź: czy nie leżą na boku, czy jest
   dźwięk, czy data nagrania się zgadza.
7. Dopiero gdy wszystko gra — puść resztę. Kosz opróżnij na samym końcu;
   to on zwalnia miejsce i to jest moment bez odwrotu.
