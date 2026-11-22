# Паралельна пошукова система з локальною Ollama

Java 21 індексує англомовні .txt-файли власним пулом потоків. CLI шукає за ключовими словами. Вебклієнт показує, які слова додала мовна модель, як це вплинуло на видачу і коли пошук завершився.

**Перевірений результат.** У Docker на Apple M5 повний корпус із 10 000 документів зайняв 2 147 483 648 байтів. Медіана побудови зменшилася з 9,457 с при одному потоці до 4,705 с при шести. Пропускна здатність keyword-пошуку зросла з 2 237 до 11 492 запитів/с для 1 і 16 клієнтів відповідно. Кожна конфігурація має три повтори. [Сирі виміри](results/full/raw.csv) · [Середовище](results/full/environment.json)

## Запуск

Потрібен запущений Docker із плагіном Compose. Перший запуск завантажує образи, Java-залежності та модель qwen2.5:0.5b (близько 400 МБ). Java збирається всередині контейнера. Для CLI потрібен Python 3.

```sh
docker compose up --build
```

У другому терміналі з кореня репозиторію виконайте такі команди.

```sh
curl -sS http://localhost:8080/api/health
curl -sS -H 'Content-Type: application/json' -d '{"threads":4}' http://localhost:8080/api/index
curl -sS http://localhost:8080/api/stats
./search "orbit"
./search --json "search index"
```

Для `orbit` у малому корпусі першими будуть `space.txt` і `flight.txt`. У браузері відкрийте [пошук у корпусі](http://localhost:8080/), введіть `orbit` і натисніть **Знайти**. Вебформа також уміє побудувати індекс. Індекс зберігається в пам’яті. Кожен запуск нового процесу сервера починається з нульової статистики.

У фоновому режимі використовуйте `docker compose up --build -d`. Перевірити стан, список моделей і зупинити проєкт можна окремими командами.

```sh
docker compose ps
docker compose exec ollama ollama list
docker compose logs --tail=30 ollama
docker compose down
```

Поки модель недоступна, health повертає HTTP 200 і `ollama:false`. REST-пошук працює одразу після індексації. WebSocket за помилки моделі завершує всі три етапи, використовуючи початкові слова. Сценарій Ollama повторює невдале завантаження кожні 15 с. Завантажена модель зберігається в іменованому томі після `docker compose down`.

Якщо порт 8080 зайнятий, задайте інший порт для Compose і клієнта. Приклад для порту 18081 наведено нижче.

```sh
PORT=18081 docker compose up --build -d
export SEARCH_URL=http://localhost:18081
curl -sS -H 'Content-Type: application/json' -d '{"threads":4}' "$SEARCH_URL/api/index"
./search "orbit"
```

## Контракт і параметри

Назви полів, типи відповідей і приклади наведено в [контракті](docs/contract.md). Точні відповіді зафіксовано у [протоколі приймання](results/acceptance.json).

| Інтерфейс | Вхід та призначення |
| --- | --- |
| GET /api/health | Статус HTTP-сервера і наявність налаштованої моделі у /api/tags. |
| POST /api/index | `{"threads":4}`. Від 1 до 64 робітників, одна задача на файл. Повертає відповідь після завершення всіх робітників і публікації індексу. |
| GET /api/stats | Кількість документів, тривалість та кількість потоків останньої успішної побудови. |
| POST /api/search | `{"query":"orbit","mode":"keyword"}`. До 10 результатів, за однакових оцінок doc_id зростає. |
| WebSocket /ws/search | `{"query":"orbit"}`. Послідовно повертає `query_expansion`, `keyword_search`, `{"stage":"done"}`. |

```sh
curl -sS -H 'Content-Type: application/json' \
  -d '{"query":"orbit","mode":"keyword"}' http://localhost:8080/api/search
```

Запит має містити від 1 до 512 символів, серед них хоча б один непробільний. Невідомий режим, некоректний JSON або кількість потоків поза межами дають HTTP 400. Для невдалого читання корпусу сервер повертає 500 і зберігає попередній знімок. URI без маршруту дає 404, неправильний HTTP-метод — 405.

| Змінна Compose | Типове значення | Призначення |
| --- | --- | --- |
| PORT | 8080 | Зовнішній порт сервера. |
| DATA_DIR | ./data/sample | Каталог на хості, монтований як /data тільки для читання. |
| OLLAMA_MODEL | qwen2.5:0.5b | Модель для завантаження й генерації. |
| OLLAMA_TIMEOUT_MS | 45000 | Спільний бюджет очікування семафора та запиту генерації. |
| JAVA_TOOL_OPTIONS | -Xms64m -Xmx1024m | Початковий і максимальний розміри Java-купи. |

## Будова

- **Словник.** Масив ключів і масив значень. Лінійне пробування розв’язує колізії. За перевищення 2/3 заповнення місткість подвоюється, ключі перерозміщуються. Ця структура також підраховує локальні частоти без виділення нового String на кожну повторну появу. [WordTable.java](src/main/java/ua/kpi/search/WordTable.java)
- **Індекс.** 32 сегменти, окремий read/write lock на сегмент. Для слова зберігаються масиви номерів документів, частот і фрагментів, які зростають разом. Один робітник додає один posting на слово з документа. [InvertedIndex.java](src/main/java/ua/kpi/search/InvertedIndex.java)
- **Пул.** Один монітор керує кільцевою чергою на 4 × threads завдань. Робітники виконують завдання поза замком. Закриття завершує прийняті завдання, приєднує робітників та передає першу помилку. [TaskPool.java](src/main/java/ua/kpi/search/TaskPool.java)
- **Токенізація.** Читання блоками по 64 KiB. Літери ASCII та цифри утворюють слова, регістр зводиться до нижнього. Слова понад 64 символи пропускаються. Фрагмент формується біля першої появи слова і зберігається у знімку. [Tokenizer.java](src/main/java/ua/kpi/search/Tokenizer.java)
- **Публікація та ранжування.** Побудови серіалізовані. Після join присвоєння volatile-полю публікує цілісний індекс і його статистику. Кожен пошук бере один знімок. Ранжування підсумовує BM25 для унікальних слів, k₁ = 1,2, b = 0,75. [SearchEngine.java](src/main/java/ua/kpi/search/SearchEngine.java)
- **Клієнти й модель.** Блокувальні HTTP-обробники працюють на робочих потоках Undertow. WebSocket використовує віртуальний потік та послідовну відправку. Справедливий семафор на один дозвіл впорядковує запити до локальної моделі. Парсер приймає до шести різних слів і зберігає початковий запит. [Server.java](src/main/java/ua/kpi/search/Server.java) · [SearchSocket.java](src/main/java/ua/kpi/search/SearchSocket.java) · [OllamaClient.java](src/main/java/ua/kpi/search/OllamaClient.java)

## Локальна збірка й тести Java

Потрібен JDK 21 або новіший. Вкажіть шлях до JDK у JAVA_HOME. Maven Wrapper завантажить Maven 3.9.9.

```sh
./mvnw test
./mvnw package
"$JAVA_HOME/bin/java" -Xmx1g -jar target/search.jar
```

Остання команда запускає сервер на хості. Для одночасної роботи з Compose виберіть інший PORT. За замовчуванням хостовий JAR шукає локальну Ollama за `http://localhost:11434`. Адресу можна задати через OLLAMA_URL. У виконанні через Compose використовується внутрішнє ім’я сервісу.

## Наскрізні перевірки

Для тестів клієнтів і побудови графіків використовуйте Python 3.11+ та проєктне віртуальне середовище.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements.txt
export PLAYWRIGHT_BROWSERS_PATH="$PWD/.tools/browsers"
.venv/bin/python -m playwright install chromium
PYTHON=.venv/bin/python scripts/test-compose.sh
```

Сценарій створює окремий Compose-проєкт і том, очікує HTTP-сервера, перевіряє реальне завантаження моделі, REST, CLI, WebSocket та UI. Після запуску прибирає власні контейнери і свій тестовий том. Порт тесту типово 18080, для зміни встановіть TEST_PORT. На мінімальному Linux для Chromium доступна команда `.venv/bin/python -m playwright install --with-deps chromium`.

Щоб перевірити вже розгорнутий сервер з **data/sample**, виконайте команди нижче. Вони перебудовують тестовий індекс. Прапорець `--require-model` вимагає реального непорожнього розширення для контрольного запиту. Без нього перевіряється також штатний режим із порожнім списком додатків.

```sh
.venv/bin/python scripts/check_api.py --url http://localhost:8080 --require-model
.venv/bin/python scripts/check_ui.py --url http://localhost:8080
```

У зафіксованому запуску пройшли 10 Java-тестів. Наскрізний тест перевірив п’ять еталонних запитів, 72 змішані конкурентні операції, повторну побудову, помилкові параметри, консольний вивід, два послідовні запити через один WebSocket і браузер на двох ширинах. [Протокол перевірок](results/checks.txt) · [Еталонні документи](data/sample) · [Тести Java](src/test/java/ua/kpi/search)

## Генерація та повний benchmark

Малий корпус — 8 документів, 894 байти. Для повного досліду застосовано суворішу межу з додатка А — **2 GiB і 10 000 файлів**. Генератор пише блоками й перевіряє вільне місце для тексту, файлових блоків і резерву 512 MiB. Образи Docker та ваги моделі займають окремий простір. Повна побудова і повторна публікація пройшли з Java-купою до 1 GiB. Потрібна пам’ять залежить також від числа різних слів і пар слово–документ.

```sh
if [ ! -f data/generated-full/manifest.json ]; then python3 scripts/generate.py; fi
DATA_DIR=./data/generated-full PORT=18081 docker compose up --build -d
run_dir="results/local-$(date +%Y%m%d-%H%M%S)"
python3 scripts/benchmark.py --port 18081 --data data/generated-full --out "$run_dir" \
  --max-threads 6 --clients 1 2 4 8 16 --repeats 3 --seconds 3 --seed 2026
.venv/bin/python scripts/plot.py "$run_dir"
```

Для короткої репетиції з меншим диском згенеруйте окремий корпус, тоді у командах Compose і benchmark вище підставте `data/generated-small` в обох місцях.

```sh
python3 scripts/generate.py --out data/generated-small --documents 1000 --bytes 33554432 --seed 2026
```

Генератор приймає новий вихідний каталог. Великі корпуси, робочі виміри та зібраний JAR виключено з Git. Під час індексації вміст каталогу має бути стабільним. Після зміни файлів надішліть новий запит /api/index.

Скрипт вимірювання перевіряє відповідність порту й каталогу запущеному контейнеру, рахує байти та SHA-256 усіх текстів. Одна побудова прогріває JVM і файловий кеш. Вимірювані конфігурації всередині кожного повтору йдуть у псевдовипадковому порядку. Час індексації береться із сервера. Кожен пошуковий клієнт прогріває своє HTTP/1.1-з’єднання, стартує зі спільного бар’єра та перевіряє JSON кожної відповіді. Throughput дорівнює загальній кількості успішних запитів, поділеній на тривалість до останньої відповіді.

| Файл результатів | Що містить |
| --- | --- |
| [environment.json](results/full/environment.json) | Машина, ресурси Docker, версія JVM, хеш JAR, Git-ревізія, параметри навантаження, фактичні байти й хеш корпусу. |
| [raw.csv](results/full/raw.csv) | 18 побудов і 15 вимірювань throughput. Для кожного окремо записано конфігурацію, повтор і тривалість. |
| [summary.json](results/full/summary.json) | Медіани та мінімум/максимум. Час індексації — у секундах, у сирому стовпці index_ms — у мілісекундах. |

Щоб побудувати графіки з уже збереженої повної серії, виконайте команду нижче. Вона запише index.png та search.png у results/full.

```sh
.venv/bin/python scripts/plot.py results/full
```

Після експерименту поверніть малий корпус і побудуйте демонстраційний індекс.

```sh
DATA_DIR=./data/sample PORT=8080 docker compose up -d
curl -sS -H 'Content-Type: application/json' -d '{"threads":4}' http://localhost:8080/api/index
```

## Ревізії технічних етапів

Гілка `main` відтворює початковий порядок технічних етапів. Під час перенесення історії коміти отримали нові дати й SHA. У `results/full/environment.json` залишено ідентифікатор, який записав скрипт під час вимірювання. Таблиця дає змогу знайти відповідний код у цій гілці.

| Етап | Початковий SHA | SHA у цій гілці |
| --- | --- | --- |
| Пул потоків | `62010df` | [bb1e80f](https://github.com/harperreynell/PO_Course_Work/commit/bb1e80fcc2c91ad1cea804ac03cbdcffff5bd0bf) |
| Інвертований індекс і BM25 | `a2adc29` | [63eeac7](https://github.com/harperreynell/PO_Course_Work/commit/63eeac707912529c14b7ec685788d090eb3e31c4) |
| REST та WebSocket | `f389206` | [a9378f9](https://github.com/harperreynell/PO_Course_Work/commit/a9378f9739a931bb20914aaf2b8dda8d8b418314) |
| Методика benchmark | `61ce3b9` | [2ecc07c](https://github.com/harperreynell/PO_Course_Work/commit/2ecc07c99ce3563aa4ebbd79348cd7050562d4f7) |
| Результати повного експерименту | `f8ba29f` | [74c2990](https://github.com/harperreynell/PO_Course_Work/commit/74c2990a1cf2933071245666b848f2a81061e371) |

## Джерела

Модель інвертованого індексу спирається на розділ про побудову індексів підручника з інформаційного пошуку. Правила видимості Java та зовнішні інтерфейси звірено з первинною документацією. [Introduction to Information Retrieval, гл. 4](https://nlp.stanford.edu/IR-book/pdf/04const.pdf) · [Java, happens-before](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html#jls-17.4.5) · [Ollama Generate](https://docs.ollama.com/api/generate) · [Ollama List](https://docs.ollama.com/api/tags) · [Docker Compose](https://docs.docker.com/compose/intro/compose-application-model/)
