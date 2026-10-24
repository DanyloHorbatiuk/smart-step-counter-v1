# Smart Step Counter

Трекер кроків з аналізом інтенсивності руху через акселерометр.
Android 12+ (API 31+), Kotlin, Jetpack Compose.

## Можливості

- **Координати сенсора X, Y, Z** (м/с²) з двонаправленими індикаторами (Canvas, діапазон ±2g),
  модуль прискорення |a| та динамічна складова |a| − g.
- **Індикатор нахилу** («кулька» на Canvas), що котиться до нижчого краю пристрою.
- **Лічильник кроків** і темп (кроків/хв).
- **Стан руху**: Спокій / Ходьба / Біг — з кольоровою шкалою інтенсивності та позначками порогів.
- **Живий графік сигналу** за останні ~3 с з пунктирними лініями порогу кроку.
- Таблиця порогових значень прямо в інтерфейсі.

## Робота з SensorManager і життєвий цикл

`MainActivity` реалізує `SensorEventListener`:

| Метод | Дія |
|---|---|
| `onCreate` | `getSystemService(SensorManager)`, `getDefaultSensor(TYPE_ACCELEROMETER)` |
| `onResume` | `registerListener(..., SENSOR_DELAY_GAME)` (~50 Гц) |
| `onPause` | `unregisterListener(this)`: у фоні сенсор не зчитується, батарея не витрачається |
| `onSensorChanged` | передає `values[0..2]` і `timestamp` у `StepCounterViewModel` |
| `onAccuracyChanged` | показує попередження, якщо дані ненадійні |

Стан (кількість кроків) зберігається у `ViewModel`, тому не губиться при повороті екрана.
Після повернення з фону фільтри перезапускаються (`StepDetector.resume()`), щоб пауза не дала хибний пік.

## Алгоритм

Код: `app/src/main/java/com/example/smartstepcounter/sensor/StepDetector.kt`.
Він не залежить від Android, тому покритий JVM unit-тестами.

1. `|a| = √(x² + y² + z²)`: модуль не залежить від того, як лежить телефон.
2. Швидкий фільтр нижніх частот (τ = 0.04 с) прибирає дрібне тремтіння.
3. Повільний фільтр (τ = 0.8 с) відстежує гравітацію; **динамічне прискорення** = згладжене |a| − гравітація.
4. **Крок** рахується, коли динамічне прискорення перетинає поріг знизу вгору, детектор «зведений»
   і з попереднього кроку минуло достатньо часу.
5. **Інтенсивність** — RMS динамічного прискорення у ковзному вікні 2 с.

## Порогові значення (Threshold)

Усі пороги зібрані в об'єкті `Thresholds`.

| Параметр | Значення | Пояснення |
|---|---|---|
| `STEP_PEAK` | **1.2 м/с²** | Поріг кроку. У спокої шум ≈ 0.05–0.3 м/с², при ходьбі пік ≈ 1.5–4 м/с² |
| `STEP_RESET` | **0.4 м/с²** | Гістерезис: наступний крок можливий лише після спаду сигналу нижче цього значення (один «дрижачий» пік ≠ кілька кроків) |
| `MIN_STEP_INTERVAL_NS` | **250 мс** | Не більше 4 кроків/с (240 кроків/хв); відсікає вібрацію та трясіння |
| `WALK_RMS` | **0.6 м/с²** | **Спокій → Ходьба** |
| `RUN_RMS` | **3.5 м/с²** | **Ходьба → Біг** (біг: пікові значення 5–15 м/с², 150–200 кроків/хв) |
| `WINDOW_NS` | **2 с** | Вікно для RMS і темпу (≈ 3–4 кроки при ходьбі) |

Діаграма переходів:

```
            RMS ≥ 0.6             RMS ≥ 3.5
  СПОКІЙ  ─────────────▶ ХОДЬБА ─────────────▶ БІГ
          ◀─────────────        ◀─────────────
            RMS < 0.6             RMS < 3.5
```

## Запуск

1. Відкрийте теку проєкту в Android Studio (Ladybug або новіша) і дочекайтеся Gradle Sync.
2. Запустіть на пристрої або емуляторі з Android 12+.
3. **Емулятор:** `⋮` (Extended controls) → **Virtual sensors** → **Device Pose**.
   Змінюйте нахил, щоб побачити зміну X/Y/Z та рух «кульки». Щоб імітувати кроки,
   швидко й ритмічно рухайте повзунки (або на вкладці *Additional sensors* змінюйте значення акселерометра):
   кожен ривок вище 1.2 м/с² зарахується як крок.
4. Згорніть застосунок: у картці статусу буде «Сенсор зупинено (onPause)», а слухач сенсора знято.

## Тести

```
./gradlew test
```

`StepDetectorTest` подає синтетичні сигнали (50 Гц):
- нерухомий пристрій → 0 кроків, стан «Спокій»;
- 1.8 Гц, амплітуда 2.5 м/с² за 20 с → ≈36 кроків, «Ходьба», ≈108 кроків/хв;
- 2.8 Гц, амплітуда 8 м/с² → «Біг», темп > 150 кроків/хв;
- слабке тремтіння 0.8 м/с² → 0 кроків;
- вібрація 10 Гц → не більше 4 кроків/с;
- межі порогів класифікації.

## ЛР2: BLE Interface Layer

Емулятор Android не має повноцінного Bluetooth, тому взаємодію з IoT-пристроєм реалізовано
програмним шаром, що імітує BLE-хаб **StepHub-01**. Застосунок передає на хаб кроки й стан руху
та приймає від нього команди. Інтерфейс `IBleConnector` не залежить від реалізації: `MockBleConnector`
можна замінити обгорткою над `BluetoothGatt`, не змінюючи `BleViewModel` і UI.

### GATT-профіль

| Елемент | UUID | Властивості | Вміст |
|---|---|---|---|
| Service StepHub | `a3f10001-6c2e-4d8b-9e57-1b0c4f2a7d90` | — | основний сервіс хаба |
| STEPS | `a3f10002-6c2e-4d8b-9e57-1b0c4f2a7d90` | Read, Notify | кількість кроків і темп |
| MOTION | `a3f10003-6c2e-4d8b-9e57-1b0c4f2a7d90` | Read, Notify | стан руху та RMS |
| COMMAND | `a3f10004-6c2e-4d8b-9e57-1b0c4f2a7d90` | Write | команди на хаб / від хаба |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | дескриптор | вмикає Notify (стандарт Bluetooth SIG) |

### Формат пакетів

Короткі текстові рядки UTF-8 `КЛЮЧ=значення;КЛЮЧ=значення` (десятковий роздільник — крапка):

| Характеристика | Приклад | Коли надсилається |
|---|---|---|
| STEPS | `STEPS=28;CAD=108` (кроки; кроків/хв) | при зміні кількості кроків, не частіше 1 раз/с |
| MOTION | `STATE=WALKING;RMS=1.42` (STILL/WALKING/RUNNING; м/с²) | при зміні стану руху, не частіше 1 раз/с |
| COMMAND | `RESET`, `PING`, `PONG` | команди |

На кожен запис хаб відповідає `ACK`, на `PING`, записаний у COMMAND, — `PONG`.

Команди від хаба (Notify на COMMAND):
- `RESET` — скидає лічильник кроків (той самий `reset()`, що й кнопка «Скинути» в ЛР1);
- `PING` — застосунок відповідає `PONG`.

### Стани з'єднання

```
               scan()                   2 с
 DISCONNECTED ───────▶ SCANNING ─────────────────▶ DISCONNECTED
      │  ▲                                          (список пристроїв)
      │  │ disconnect() / «Giving up»
      │  │
      │  └────────────────────────────────┐
      ▼ connect(StepHub-01)               │
 CONNECTING ──── 1 с ────▶ CONNECTED ◀────┼──── ACK (150 мс) ───┐
      ▲                       │  │        │                     │
      │                       │  └── sendData() ──▶ DATA_TRANSFER
      │                       │ обрив зв'язку
      │                       ▼
      │                 DISCONNECTED ── спроба 1 (через 1 с)
      └──────────────── спроба 2 (через 2 с) / спроба 3 (через 4 с)
                        успіх → CONNECTED; 3 невдачі → DISCONNECTED
```

Підключення дозволене лише до `StepHub-01`; спроба підключитися до іншого пристрою пише
помилку в лог. `sendData()` без з'єднання повертає `false`. Записи серіалізовано `Mutex`-ом, як
у справжньому GATT-стеку (одна операція за раз).

### Потік даних

```
Accelerometer → MainActivity → StepCounterViewModel.uiState (StateFlow)
                                        │  передається у фабрику BleViewModel
                                        ▼
BleViewModel: map(steps | state) → distinctUntilChanged → throttle 1 с → IBleConnector.sendData
              connector.incoming: RESET → StepCounterViewModel.reset(), PING → sendData("PONG")
                                        │
DeviceScreen ◀── state / log (StateFlow) ── MockBleConnector
```

`BleViewModel` отримує `StateFlow` стану кроків і функцію `reset` через `ViewModelProvider.Factory`,
а сканер і конектор — з `AppContainer` (`SmartStepApp`). Тому `StepCounterViewModel` нічого не знає
про BLE і після ЛР1 не змінювався. Передача ввімкнена лише поки з'єднання активне (`collectLatest`
за станом); throttle реалізовано через `conflate()` + `delay(1000)`: перше значення йде одразу,
проміжні відкидаються, останнє гарантовано доходить.

### Як тестувати

**Unit-тести (JVM, віртуальний час `runTest` / `advanceTimeBy`):**

```
./gradlew test
```

- `MockBleScannerTest` — StepHub-01 знаходиться рівно через 2000 мс, під час пошуку стан SCANNING;
- `MockBleConnectorTest` — CONNECTING → CONNECTED за 1 с, відмова для стороннього пристрою,
  disconnect, `sendData` без з'єднання → `false`, TX/ACK і DATA_TRANSFER, PING → PONG,
  вхідні команди, обрив → 3 спроби на 1/4/9 с після обриву (затримки 1/2/4 с + 1 с на з'єднання),
  відновлення на 3-й спробі, скасування автоперепідключення;
- `BlePacketTest` — формат пакетів і розмір у байтах UTF-8.

**На емуляторі:**
1. Вкладка «Пристрій» → «Пошук пристроїв» → дозволити Bluetooth і геолокацію
   (у разі відмови з'явиться повідомлення, пошук не запуститься).
2. Через 2 с з'являться StepHub-01 («наш пристрій»), Mi Band 7 і Unknown. Натиск на Mi Band 7 —
   помилка в терміналі; натиск на StepHub-01 — «Підключення…» → «Підключено».
3. Рухайте сенсори (Extended controls → Virtual sensors): у терміналі з'являтимуться `TX [STEPS]` і
   `TX [MOTION]` не частіше одного разу на секунду, після кожного — `RX ACK`.
4. «Хаб: RESET» — лічильник на вкладці «Сенсор» скидається, на хаб іде `STEPS=0;CAD=0`.
   «Хаб: PING» — застосунок відповідає `PONG`.
5. «Імітувати обрив зв'язку» — у лозі видно спроби перепідключення 1/3, 2/3, 3/3 зі зростаючою
   затримкою; хаб «повертається» на третій спробі.
6. Logcat: фільтр `tag:BleMock` — усі BLE-події, `tag:SensorLifecycle` — реєстрація/зняття слухача
   акселерометра в `onResume`/`onPause`.


## ЛР3: Cloud Sync

Дані акселерометра й кроків синхронізуються з **Firebase Realtime Database** через її REST API
(Retrofit 2 + Gson + OkHttp, без Firebase SDK). Тому всі запити видно в
**App Inspection → Network Inspector** Android Studio, а в debug-збірці — у Logcat
(`HttpLoggingInterceptor`, рівень BODY; у release логування HTTP вимкнене).

### Структура в базі

```
devices/
└── {deviceId}/                  UUID, згенерований при першому запуску (SharedPreferences)
    ├── readings/
    │   └── {pushId}             POST devices/{id}/readings.json → {"name": "<pushId>"}
    │       ├── deviceId, type: "ACCELEROMETER", timestamp (мс, epoch)
    │       ├── value (|a|, м/с²), x, y, z
    │       └── steps, cadence, state (STILL/WALKING/RUNNING), rms
    └── status                   PUT devices/{id}/status.json (перезаписується)
        └── online, lastSync, steps
```

### Налаштування FIREBASE_DB_URL

1. Firebase Console → проєкт → **Realtime Database** → *Create database*.
2. Скопіюйте URL бази (напр. `https://<project>-default-rtdb.europe-west1.firebasedatabase.app`)
   у `local.properties` (файл не потрапляє в git):
   ```
   FIREBASE_DB_URL=https://<project>-default-rtdb.europe-west1.firebasedatabase.app
   ```
3. Перезберіть застосунок — значення потрапляє в `BuildConfig.FIREBASE_DB_URL`.
   Якщо ключа немає або URL невалідний, застосунок працює як раніше, а картка показує
   «Хмара не налаштована».

### Правила бази

Для лабораторної (без автентифікації) запис дозволено лише у гілку `devices`:

```json
{
  "rules": {
    ".read": false,
    ".write": false,
    "devices": {
      "$deviceId": {
        ".read": true,
        ".write": true
      }
    }
  }
}
```

> Такі правила відкривають гілку для всіх, хто знає URL. Для реального продукту потрібні
> Firebase Auth і правила на кшталт `auth != null`.

### Логіка відправки

- `CloudSyncViewModel` раз на **5 с** бере останній snapshot і відправляє його лише тоді, коли з
  минулої відправки змінилися кроки, стан руху або будь-яка вісь X/Y/Z на **≥ 0.5 м/с²**
  (`SyncChangeFilter.AXIS_DELTA` — вище шуму сенсора у спокої, нижче амплітуди ходьби).
  Зміна стану руху відправляється одразу, але не частіше 1 разу/с. Поки сенсор зупинено
  (`onPause`), нічого не шлеться.
- `CloudRepository` пропускає кожен запис через чергу (≤ 200, найстаріші відкидаються),
  тож записи завжди йдуть по порядку. Запис видаляється з черги лише після відповіді 2xx;
  при IOException/таймауті (10 с)/HTTP-помилці статус ERROR, запис лишається першим у черзі
  і повторюється при наступній відправці або поверненні мережі. Після успішної відправки
  оновлюється `status`. З ЛР5 черга зберігається в Room (`pending_uploads`) і переживає
  перезапуск застосунку (див. нижче).
- `AndroidNetworkMonitor` (`registerDefaultNetworkCallback`, INTERNET + VALIDATED): коли мережа
  повертається, черга відправляється автоматично.
- Усі HTTP-виклики — у корутинах на `Dispatchers.IO`. Logcat: фільтр `tag:CloudSync`.

Статуси картки «Хмарна синхронізація»: 🟢 SUCCESS, 🔴 ERROR, 🟡 PENDING (офлайн, дані в черзі),
🔵 SYNCING, ⚪ IDLE / NOT_CONFIGURED.

### Як тестувати

**Unit-тести** (`./gradlew test`, фейкові `FirebaseApi` і `NetworkMonitor`):
- `CloudRepositoryTest` — онлайн → SUCCESS і sentCount+1; офлайн → PENDING, черга росте, API не
  викликається; повернення мережі → черга відправлена по порядку, pendingCount = 0;
  IOException / HTTP 500 → ERROR, запис у черзі й повторно відправляється першим;
  переповнення черги відкидає найстаріші;
- `SyncChangeFilterTest` — без змін і шум 0.4 м/с² не шлються; вісь +0.6, нові кроки, новий стан — шлються;
- `CloudSyncViewModelTest` — тікер 5 с, фільтр Δ, миттєва відправка зміни стану з обмеженням 1/с.

**Офлайн-режим на емуляторі:**
1. Вкажіть `FIREBASE_DB_URL`, запустіть застосунок, відкрийте App Inspection → Network Inspector.
   Порухайте Virtual sensors — кожні 5 с з'являтимуться `POST …/readings.json` і `PUT …/status.json`,
   а в Firebase Console — нові записи.
2. Увімкніть Airplane mode (або вимкніть Wi-Fi і мобільні дані) — Snackbar
   «Немає інтернету — дані збережено в черзі (Pending)», точка жовта, «У черзі» зростає.
3. Вимкніть Airplane mode — після валідації мережі Snackbar «Мережу відновлено, відправлено N записів»,
   у Network Inspector — серія POST-запитів у хронологічному порядку, «У черзі: 0».
4. Помилку можна відтворити, вказавши URL неіснуючої бази або заборонивши запис у правилах
   (HTTP 401/404) — точка червона, текст помилки під лічильниками.

## ЛР4: Актуатори та Cloud-to-Device

Два типи керування виконавчими пристроями (вкладка **«Керування»**):
- **А) локальне автоматичне** — `DecisionEngine` перетворює дані акселерометра на рішення;
- **Б) дистанційне Cloud-to-Device** — команда з Firebase приходить потоком SSE, і застосунок
  реагує на неї одразу.

Актуатори (`actuator/SmartActuator`): вібромотор (`VibratorManager`, `VibrationEffect.createWaveform`,
дозвіл `VIBRATE`), ліхтарик (`CameraManager.setTorchMode` для камери з `FLASH_INFO_AVAILABLE`) і
віртуальна лампа в UI. Якщо спалаху немає (`FEATURE_CAMERA_FLASH`, напр. емулятор) або виникла
`CameraAccessException`, ліхтарик стає **віртуальним**: стан показується тільки в UI, у Logcat пишеться
попередження. `CameraManager.TorchCallback` синхронізує стан із реальним ліхтариком (наприклад,
якщо його ввімкнули з шторки). Кожна дія записується як `ActuatorEvent(timestamp, actuator, on, source, reason)`:
вона потрапляє в журнал на екрані та в Logcat (`tag:Actuator`). У ЛР5 ця модель піде в БД.

### Правила

| Правило | Умова | Дія |
|---|---|---|
| R1 «Ціль досягнута» | `steps ≥ 50` | подвійна вібрація, один раз, повторно лише після скидання лічильника |
| R2 «Біг — сигнальний ліхтар» | RUNNING безперервно ≥ 3 с | увімкнути ліхтарик; вимкнути після ≥ 5 с без RUNNING |
| R3 «Різкий удар» | `|a| ≥ 25 м/с²` (≈ 2,5 g) | вібрація-тривога, не частіше ніж раз на 10 с |

Усі пороги зібрано в `decision/DecisionConfig.kt`, а пояснення до кожного значення є в KDoc.
Для R2 використано гістерезис за часом (3 с на ввімкнення, 5 с на вимкнення): коротке
сповільнення не вимикає ліхтар і він не блимає. Перемикач **«Автоматичний режим»** вимикає
виконання рішень, але вони все одно пишуться в Logcat (`tag:Decision`), а Snackbar показує
«(авто-режим вимкнено)».

### Блок-схема рішення

```
для кожного StepSnapshot (≈ 50 Гц), now = elapsedRealtime:
  R1: IF steps < goal            THEN goalFired := false
      ELSE IF NOT goalFired      THEN goalFired := true; VIBRATE(DOUBLE_PULSE); Snackbar
  R2: IF state == RUNNING        THEN notRunningSince := null
                                      runningSince := runningSince ?: now
                                      IF NOT torchOn AND now − runningSince ≥ 3 с THEN TORCH ON
      ELSE                            runningSince := null
                                      notRunningSince := notRunningSince ?: now
                                      IF torchOn AND now − notRunningSince ≥ 5 с THEN TORCH OFF
  R3: IF |a| ≥ 25 AND (lastImpact = null OR now − lastImpact ≥ 10 с)
                                 THEN lastImpact := now; VIBRATE(ALARM); Snackbar
  кожне рішення: IF autoMode THEN SmartActuator.execute ELSE лише лог
```

Потік даних: `MainActivity.onSensorChanged → StepCounterViewModel.samples (SharedFlow, без
conflation, щоб не загубити одиночний пік удару) → ControlViewModel → DecisionEngine →
DecisionExecutor → SmartActuator → ActuatorEvent`.

### Вузол керування

```
devices/{deviceId}/control
├── led_status: false    віртуальна лампа (за перемикачем — ще й реальний ліхтарик)
└── vibrate:    false    true → одна вібрація, після чого застосунок записує false
```

Під час старту застосунок робить `GET control.json`. Якщо вузла немає, він створює його через
`PUT {"led_status": false, "vibrate": false}`, і вузол одразу з'являється в консолі.

`CloudControlListener` відкриває `GET devices/{id}/control.json` з `Accept: text/event-stream`
(okhttp-sse, окремий `OkHttpClient` з `readTimeout = 0` і без BODY-логера, який буферизував би
нескінченну відповідь). Як обробляються події:
- `put` / `patch` з `{"path": ..., "data": ...}`: path `/` — весь вузол (null означає, що його
  видалено), `/led_status` або `/vibrate` — одне поле;
- `keep-alive` ігнорується; `cancel` / `auth_revoked` — запис у лог і стан ERROR;
- після обриву застосунок перепідключається з backoff 1 → 2 → 4 → 8 → 16 → 30 с (максимум),
  а щойно `NetworkMonitor` бачить, що мережа повернулася, — одразу.

Стани слухача на картці «Cloud-to-Device»: 🟢 LISTENING, 🔵 CONNECTING, 🟡 RECONNECTING,
🔴 ERROR, ⚪ NOT_CONFIGURED. Logcat: `tag:CloudControl`. Правила бази з ЛР3 (`.read/.write` для
`devices/$deviceId`) підходять і для потоку.

### Як тестувати

**Unit-тести** (`./gradlew test`):
- `DecisionEngineTest` — ціль спрацьовує рівно один раз і знову після reset; RUNNING 2,9 с не
  вмикає ліхтарик, 3 с вмикає; вимкнення лише після 5 с без RUNNING; короткий провал не вимикає;
  удар ≥ 25 → тривога, 24,9 — ні; cooldown 10 с;
- `DecisionExecutorTest` (з `FakeActuator`) — авто-режим виконує рішення, а вимкнений лише логує;
- `ControlEventParserTest` — put `/` з повним об'єктом і з null, put `/led_status`, patch, keep-alive,
  cancel/auth_revoked, некоректний JSON, одноразовість vibrate, backoff.

**З консолі Firebase:**
1. Запустіть застосунок і відкрийте вкладку «Керування». Статус має бути 🟢 «Слухає команди (SSE)».
   Кнопкою «Копіювати» скопіюйте deviceId.
2. Firebase Console → Realtime Database → `devices/<deviceId>/control`. Поставте `led_status = true`:
   лампа на екрані засвітиться жовтим майже миттєво, а в журналі з'явиться подія `Лампа увімк CLOUD`.
3. Поставте `vibrate = true`: телефон завибрує один раз, і через мить поле в консолі саме
   повернеться у `false`.
4. Увімкніть Airplane mode: статус 🟡 «Перепідключення…». Вимкніть — і статус знову 🟢 без очікування backoff.
5. Локальні правила: потрусіть телефон (або в емуляторі задайте Virtual sensors) до 50 кроків —
   з'явиться Snackbar «Ціль 50 кроків досягнута». Різкий удар долонею або струс з |a| ≥ 25 запускає
   тривогу.

### Event-driven (SSE) vs Polling

Під час polling застосунок раз на N секунд сам питає сервер «чи щось змінилося?». Затримка
реакції дорівнює в середньому N/2. Більшість запитів повертає ті самі дані, тож трафік і батарея
витрачаються навіть тоді, коли команд немає, а зменшити затримку можна лише частішими запитами.
У моделі event-driven (Firebase REST Streaming через Server-Sent Events) застосунок тримає одне
довге HTTP-з'єднання, а сервер сам надсилає подію `put`/`patch` у момент зміни. Затримка
дорівнює лише мережевій, у простої йдуть тільки рідкі `keep-alive`, а порядок змін зберігається.
Ціна такого підходу — постійне з'єднання та потреба самому обробляти обриви
(backoff + реакція на повернення мережі).

## ЛР5: Room та аналітика

Локальне зберігання даних у Room (через KSP) і нова вкладка **«Аналітика»** з графіком часових рядів.
Архітектура MVVM: жоден Composable не звертається до БД напряму.

```
AnalyticsScreen (View, Compose)
        │  StateFlow<AnalyticsUiState>, toggleSeries(), clearHistory()
        ▼
AnalyticsViewModel ── SensorRecorder (тікер + фільтр + примусовий запис)
        │  Flow / suspend
        ▼
SensorHistoryRepository (app singleton, Dispatchers.IO, обробка SQLiteException)
        │
        ▼
SensorRecordDao / ActuatorEventDao ──► AppDatabase (Room, smart_step.db)
                                              ▲
CloudRepository ──► PendingUploadDao ─────────┘
```

Версії: Room 2.6.1, KSP 2.0.21-1.0.28, Vico 2.0.3 (`compose-m3`). Vico 2.0.3 — остання версія,
сумісна з Compose BOM 2024.12.01 і Kotlin 2.0.21. Версії 2.1+ тягнуть Compose 1.8 та Kotlin stdlib 2.1.21+.

### Схема БД (version 1, `app/schemas/.../1.json`)

**sensor_records**: історія акселерометра

| Поле | Тип Kotlin → SQLite | Опис |
|---|---|---|
| `id` | Long → INTEGER PK autoincrement | |
| `timestamp` | Long → INTEGER, **індекс** | час, мс від epoch |
| `sensorValue` | Float → REAL | \|a\| з гравітацією, м/с² |
| `x`, `y`, `z` | Float → REAL | осі, м/с² |
| `rms` | Float → REAL | RMS динамічного прискорення |
| `steps` | Int → INTEGER | лічильник кроків |
| `state` | String → TEXT | `MotionState.name` (STILL/WALKING/RUNNING) |
| `actionTriggered` | Boolean → INTEGER 0/1 | у цей момент спрацював актуатор |

**actuator_events**: історія спрацювань з ЛР4

| Поле | Тип | Опис |
|---|---|---|
| `id` | Long → INTEGER PK | |
| `timestamp` | Long → INTEGER, **індекс** | |
| `actuator` | String → TEXT | `ActuatorType.name` (TORCH/VIBRATION/LAMP) |
| `isOn` | Boolean → INTEGER | |
| `source` | String → TEXT | `EventSource.name` (AUTO/MANUAL/CLOUD) |
| `reason` | String → TEXT | правило / причина |

**pending_uploads**: персистентна хмарна черга

| Поле | Тип | Опис |
|---|---|---|
| `id` | Long → INTEGER PK | порядок FIFO |
| `createdAt` | Long → INTEGER | час постановки в чергу |
| `payloadJson` | String → TEXT | `SensorData`, серіалізований Gson |

Таблиці між собою не пов'язані зовнішніми ключами: це три незалежні журнали. Enum-и зберігаються
рядками (`name`), тому TypeConverters не потрібні.

### Політика запису

- `SensorRecorder` раз на `recordIntervalMs` (1000 мс за замовчуванням) бере поточний snapshot і пише
  його в БД **лише при суттєвій зміні**. Для цього використовується той самий `SyncChangeFilter`, що й
  у ЛР3 (Δ осі ≥ 0.5 м/с², зміна кроків або стану). Телефон, що лежить на столі, БД не заповнює.
- Коли спрацьовує будь-який актуатор (AUTO, MANUAL чи CLOUD), запис робиться **примусово**, в обхід
  фільтра, з `actionTriggered = true`. На графіку така точка позначена червоним маркером. Події з
  replay-кешу `SmartActuator` (до старту запису) ігноруються.
- `recordIntervalMs` — це `StateFlow`, а `setRecordInterval(ms)` (мінімум 100 мс) змінює його в
  рантаймі: тікер перезапускається одразу. У ЛР6 через нього ML-модель прискорюватиме запис під час бігу.
- Кожна `ActuatorEvent` записується в `actuator_events` в app-scope, незалежно від відкритої вкладки.
- Усі запити до БД виконуються через suspend-функції DAO або Flow на `Dispatchers.IO`.

### Політика зберігання

- Не більше **20 000** записів сенсора (близько 5,5 год при 1 запис/с) і не старші **24 год**.
  Події актуаторів теж зберігаються не довше 24 год.
- Очищення (`deleteOlderThan` + `trimToMaxRows`) запускається одразу після старту, а далі раз на хвилину
  у фоновій корутині.
- **Нестача місця.** `SQLiteFullException` перехоплюється. Статус сховища стає `FULL`, таблиця
  агресивно обрізається до 2 000 найновіших записів, і з'являється Snackbar «Мало місця: старі записи
  видалено». Будь-яка інша `SQLiteException` переводить статус у `ERROR`. Застосунок не падає, а
  Flow-запити повертають порожні дані. Після наступного успішного очищення статус повертається в `OK`.
  Стан сховища — `StateFlow<StorageState>`.

### Вкладка «Аналітика»

- Лінійний графік (Vico) останніх **50** записів. Дані йдуть Flow з Room, тож графік оновлюється в
  реальному часі. Вісь X — час `HH:mm:ss`: точки розставлено за порядковим номером, а підпис показує
  час запису. Через фільтр між записами бувають паузи, і без цього вісь розтягувалася б на порожні
  проміжки. Вісь Y — значення в м/с².
- Перемикачі серій **X, Y, Z** (увімкнені за замовчуванням: поворот телефона дає хвилі), **|a|**,
  **RMS**. Червоні маркери позначають точки з `actionTriggered = true`.
- Під графіком: кількість записів у БД, інтервал запису, стан сховища й останні 20 подій актуаторів
  з БД. Історія доступна без інтернету.
- Кнопка «Очистити історію» з діалогом підтвердження видаляє `sensor_records` і `actuator_events`.
  Хмарну чергу вона не чіпає.

### Персистентна хмарна черга

`CloudRepository` зберігає чергу в `pending_uploads`, а не в `ArrayDeque`:
`upload` → `insert` + `trimToMax(200)`; `flush` → `peekOldest()` → POST → `deleteById` **лише після 2xx**.
Порядок відправки визначає `id`. Записи, накопичені в офлайні, переживають закриття застосунку.
На старті репозиторій читає їх із таблиці (статус PENDING і `pendingCount`) і відправляє одразу,
якщо мережа є, або коли вона з'явиться. Статуси, Snackbar-и та flush працюють так само, як у ЛР3.

### Database Inspector

1. Запустіть debug-збірку на емуляторі або пристрої (API 26+) з Android Studio.
2. **View → Tool Windows → App Inspection**, вкладка **Database Inspector**, виберіть процес
   `com.example.smartstepcounter`.
3. Відкрийте `smart_step.db` → `sensor_records` / `actuator_events` / `pending_uploads`. Увімкніть
   **Live updates**, щоб бачити нові рядки, або виконайте SQL, наприклад
   `SELECT * FROM sensor_records WHERE actionTriggered = 1 ORDER BY timestamp DESC`.

### Як тестувати

```
./gradlew test                         # JVM
./gradlew connectedDebugAndroidTest    # DAO на емуляторі (in-memory Room)
```

- `SensorRecorderTest`: запис раз на інтервал, фільтр (шум < 0.5 не пишеться), пауза сенсора,
  примусовий запис з `actionTriggered`, ігнорування replay, зміна інтервалу на 200 мс.
- `SensorHistoryRepositoryTest`: видалення записів, старших за 24 год, ліміт 20 000, фонове очищення
  раз на хвилину, запис подій актуаторів, `SQLiteFullException` → FULL + обрізання + подія,
  `SQLiteException` → ERROR → OK, обмеження інтервалу, очищення історії.
- `CloudRepositoryTest` (фейковий `PendingUploadDao`): тести ЛР3, а також перевірки, що черга переживає
  «перезапуск» (новий екземпляр на тій самій таблиці відправляє старі записи по порядку) і що рядок
  видаляється лише після успіху.
- androidTest: `SensorRecordDaoTest` (порядок `observeLatest`, `trimToMaxRows`, `deleteOlderThan`),
  `PendingUploadDaoTest` (FIFO, `trimToMax`), `ActuatorEventDaoTest`.

Вручну: на вкладці «Аналітика» покрутіть телефон, і лінії X/Y/Z підуть хвилями. Увімкніть ліхтарик на
«Керуванні», і на графіку з'явиться маркер. Далі увімкніть авіарежим, походіть, закрийте застосунок,
вимкніть авіарежим і відкрийте його знову: черга відправиться, і з'явиться Snackbar.

## ЛР6: On-device ML

Маленька нейромережа TensorFlow Lite класифікує рух **на пристрої**, без хмари. Вона розрізняє
4 класи, серед них **SHAKE** (струшування, можлива спроба крадіжки або злому), якого порогова
класифікація ЛР1 не бачить. Модель керує частотою запису в БД (ЛР5) і викликає тривогу через
актуатори (ЛР4).

```
StepCounterViewModel.samples (кожен відлік, dynamic = |a| − g після фільтрів ЛР1)
        │
        ▼
MlPipeline ── CircularBuffer(50) ── кожні 25 відліків (0,5 с) ──► ActivityClassifier.classify()
        │                                                           (Dispatchers.Default, TFLite)
        ├─ MajorityVoter(3) ──► StateFlow<AiInsight> ──► «AI Insights» на вкладці «Сенсор»
        ▼
AiController ── AiRules ──┬─► SensorHistoryRepository.setRecordInterval(250 / 1000 мс)
                          └─► DecisionExecutor ─► SmartActuator (source = AI) ─► actuator_events
```

### Модель

| | |
|---|---|
| Файл | `app/src/main/assets/activity_classifier.tflite` (10,8 КіБ, float32), `labels.txt` |
| Архітектура | Dense 32 ReLU → Dense 16 ReLU → Dense 4 Softmax, 2228 параметрів |
| Вхід | `[1, 50]` float32: 1 с динамічного прискорення (50 Гц), **поділеного на 20 м/с²** |
| Вихід | `[1, 4]` float32: softmax-ймовірності `STILL, WALKING, RUNNING, SHAKE` |
| Runtime | `org.tensorflow:tensorflow-lite:2.17.0`; модель memory-map з assets (`noCompress += "tflite"`) |

Вхід — той самий `dynamic`, що рахує `StepDetector`. Тому модель, як і пороги, не залежить від
орієнтації телефону. Генератор датасету пропускає синтетичний |a| через Python-порт тих самих
фільтрів (τ = 0,04 с і 0,8 с), тож навчання й inference бачать однаковий сигнал.

Контракт моделі (`input_scale`, `window`, `hop`, `labels`) зберігається в **одному місці**:
`ml/model_config.json`. Його читає `train.py`; Kotlin-константи в `MlConfig` звіряє з ним
`MlConfigTest`. При завантаженні `ActivityClassifier` перевіряє форми й dtype тензорів і порядок
`labels.txt`. Якщо модель не завантажилась (немає файлу, інші форми, немає нативної бібліотеки),
застосунок не падає: картка показує «ML недоступний», а все інше працює на порогах ЛР1.

Dense обрано, бо точність уже ≥ 95%. `train.py` автоматично переходить на Conv1D, лише якщо
Dense не досягне 95%.

### Метрики (з `ml/report.md`, test = 2400 вікон, виміряно на .tflite)

**Accuracy 99,88%**

| Клас | Precision | Recall | F1 |
|---|---|---|---|
| STILL | 0.998 | 1.000 | 0.999 |
| WALKING | 0.998 | 1.000 | 0.999 |
| RUNNING | 0.998 | 1.000 | 0.999 |
| SHAKE | 1.000 | 0.995 | 0.997 |

Усі 3 помилки — вікна SHAKE (по одному прийнято за STILL, WALKING і RUNNING). Дані синтетичні,
тож цифри оптимістичні: на реальних записах точність буде нижчою.

### Як перенавчити

```
python3 -m venv ml/.venv
ml/.venv/bin/pip install -r ml/requirements.txt
ml/.venv/bin/python ml/generate_dataset.py   # ml/data/dataset.npz, 4 × 4000 вікон, seed 42, 70/15/15
ml/.venv/bin/python ml/train.py              # .tflite + labels.txt в assets, ml/report.md
```

`tensorflow==2.17.1` у Python відповідає runtime 2.17.0 на Android: конвертер не запише версій
операцій, яких runtime не знає. Якщо змінюєте нормалізацію чи вікно, редагуйте `ml/model_config.json`
і `MlConfig.kt` разом (інакше впаде `MlConfigTest`).

### Інтеграція

- **Згладжування:** результат — більшість із 3 останніх прогнозів (1,5 с), ймовірності усереднюються.
- **ЛР5, частота запису:** згладжений RUNNING з confidence ≥ 0,7 → `setRecordInterval(250 мс)`.
  Після виходу з RUNNING запис ще 3 с іде з інтервалом 250 мс, потім повертається на 1000 мс. Зміна
  пишеться в logcat (`adb logcat -s AI`) і видна в картці та на вкладці «Аналітика».
- **ЛР4, правило R4 «AI: аномалія руху»:** SHAKE з confidence ≥ 0,8 у **2 вікнах поспіль** (сирих,
  без згладжування, щоб не запізнюватись) вмикає вібрацію ALARM і ліхтарик, source = `AI`,
  reason = «Аномалія: SHAKE 0,91». Cooldown 10 с. Коли він минає і струшування немає, ліхтарик
  вимикається. Рішення йде через `DecisionExecutor`, тож поважає перемикач авто-режиму. Подія
  потрапляє в журнал «Керування» і в таблицю `actuator_events` (новий `EventSource.AI` зберігається
  як рядок, міграція не потрібна).
- **Порівняння з ЛР1:** картка показує, чи збігається клас моделі зі станом `StepDetector`.
  Для SHAKE відповідника немає, тому завжди «не збігається».

### Симулятор руху для емулятора

```
python3 tools/emu_motion.py --mode demo            # still → walk → run → shake по 8 с
python3 tools/emu_motion.py --mode shake --seconds 10
```

Скрипт підключається до консолі емулятора (`localhost:5554`, токен з
`~/.emulator_console_auth_token`) і шле `sensor set acceleration x:y:z` з частотою ~25 Гц.
Профілі ті самі, що в датасеті. Застосунок читає сенсор з частотою ~50 Гц, тому кожне значення
приходить двічі. Цей ефект враховано в датасеті (30% вікон з sample-and-hold 25 Гц).

### Тести

- JVM: `CircularBufferTest`, `MlConfigTest` (константа нормалізації, вікно, мітки = `model_config.json`
  і `labels.txt`), `MajorityVoterTest`, `MlPipelineTest` (перше вікно після 50 відліків, далі кожні 25,
  «ML недоступний», помилка inference), `AiRulesTest` (250/1000 мс, затримка 3 с, SHAKE 1 vs 2 вікна,
  cooldown, вимкнення ліхтарика), `AiIntegrationTest` (фейковий класифікатор → пайплайн → репозиторій
  і актуатор).
- androidTest `ActivityClassifierTest`: модель з assets, форми `[1,50]`/`[1,4]`, сума ймовірностей ≈ 1,
  синтетична ходьба (1,8 Гц, 2,5 м/с² через `StepDetector`) → WALKING, біг → RUNNING, нулі → STILL.

### On-device vs cloud AI

On-device inference працює без мережі й без затримки на запит (≈ 0,1 мс на вікно). Сирі дані
сенсора не покидають телефон, що добре для приватності, а сервер не потрібен. Тому реакція на
SHAKE-аномалію спрацьовує навіть в авіарежимі. Ціна — обмеження пристрою: модель має бути
крихітною, її оновлення вимагає нового APK (або окремого механізму доставки), а навчання й
моніторинг якості на реальних даних складніші. Хмарна модель може бути як завгодно великою,
оновлюється миттєво і бачить дані всіх користувачів. Натомість вона залежить від мережі, додає
сотні мілісекунд затримки, коштує грошей за запити і вимагає передавати сенсорні дані третій стороні.

## Структура

```
app/src/main/java/com/example/smartstepcounter/
├── MainActivity.kt           SensorManager, SensorEventListener, onResume/onPause
├── SmartStepApp.kt           Application, тримає AppContainer
├── AppContainer.kt           Manual DI: Room, BLE-мок, мережа, Firebase API, CloudRepository, актуатори, SSE, історія
├── StepCounterViewModel.kt   стан UI (StateFlow), історія сигналу
├── sensor/StepDetector.kt    фільтри, детекція кроків, пороги, класифікація
├── actuator/
│   ├── ActuatorModels.kt     ActuatorType, EventSource, VibrationPattern, ActuatorEvent, ActuatorState
│   ├── Actuator.kt           інтерфейс актуаторів (реальний / фейковий)
│   ├── SmartActuator.kt      вібрація, ліхтарик (апаратний / віртуальний), TorchCallback
│   ├── ControlSettings.kt    перемикачі авто-режиму та дублювання лампи
│   └── ControlViewModel.kt   Sensor → DecisionEngine → SmartActuator, журнал подій
├── data/local/
│   ├── SensorRecord.kt       @Entity sensor_records
│   ├── ActuatorEventEntity.kt @Entity actuator_events
│   ├── PendingUpload.kt      @Entity pending_uploads
│   ├── *Dao.kt               SensorRecordDao, ActuatorEventDao, PendingUploadDao
│   └── AppDatabase.kt        RoomDatabase v1
├── history/
│   ├── ISensorHistoryRepository.kt / SensorHistoryRepository.kt  запис, очищення, SQLiteException
│   ├── SensorRecorder.kt     тікер recordIntervalMs + фільтр + примусовий запис
│   ├── RetentionPolicy.kt    20 000 записів / 24 год / раз на хвилину
│   └── StorageState.kt       OK / FULL / ERROR, події для Snackbar
├── analytics/AnalyticsViewModel.kt  стан вкладки «Аналітика», серії графіка
├── ml/
│   ├── MlConfig.kt           контракт моделі: нормалізація 20 м/с², вікно 50, hop 25
│   ├── AiModels.kt           AiLabel, Prediction, AiInsight, інтерфейс Classifier
│   ├── CircularBuffer.kt     кільцевий буфер FloatArray
│   ├── ActivityClassifier.kt TFLite Interpreter, memory-map з assets, перевірка тензорів
│   ├── MajorityVoter.kt      більшість із 3 останніх прогнозів
│   ├── MlPipeline.kt         відліки → вікна → inference на Dispatchers.Default
│   ├── AiController.kt       прогноз → інтервал запису (ЛР5) і тривога (ЛР4)
│   └── AiViewModel.kt        стан блоку «AI Insights»
├── decision/
│   ├── DecisionConfig.kt     пороги R1–R3 з поясненнями
│   ├── Decision.kt           Rule, DecisionAction, Decision
│   ├── DecisionEngine.kt     правила R1–R3 (чистий Kotlin)
│   ├── AiRules.kt            правила моделі: інтервал запису, R4 SHAKE-аномалія
│   └── DecisionExecutor.kt   виконання / лише лог при вимкненому авто-режимі
├── ble/
│   ├── BleUuids.kt           GATT-профіль StepHub, команди
│   ├── BleModels.kt          BleDevice, BleConnectionState, BleLogEntry
│   ├── BlePacket.kt          формат пакетів STEPS / MOTION
│   ├── IBleConnector.kt      інтерфейс шару + BleHubSimulator
│   ├── MockBleConnector.kt   емуляція GATT-з'єднання, лог, автоперепідключення
│   ├── MockBleScanner.kt     емуляція сканування
│   └── BleViewModel.kt       зв'язок ЛР1 ↔ BLE, throttle, команди хаба
├── cloud/
│   ├── SensorData.kt         SensorData, DeviceStatus, PushResponse
│   ├── FirebaseApi.kt        Retrofit-інтерфейс REST + фабрика OkHttp/Gson
│   ├── CloudSyncState.kt     статуси, стан синхронізації, події для Snackbar
│   ├── NetworkMonitor.kt     ConnectivityManager → StateFlow<Boolean>
│   ├── ICloudRepository.kt   інтерфейс шару
│   ├── CloudRepository.kt    черга в Room, flush, POST/PUT; NotConfiguredCloudRepository
│   ├── DeviceIdProvider.kt   стабільний UUID пристрою
│   ├── SyncChangeFilter.kt   фільтр Δ = 0.5 м/с²
│   ├── CloudSyncViewModel.kt тікер 5 с, миттєва відправка зміни стану
│   ├── ControlModels.kt      ControlState, стани слухача, ControlCommand
│   ├── ControlApi.kt         GET/PUT control, скидання vibrate
│   ├── ControlEventParser.kt розбір SSE-подій, ControlReducer, Backoff
│   ├── CloudControlListener.kt SSE-потік, перепідключення
│   └── CloudCommandHandler.kt  команда → актуатор, vibrate=false
└── ui/
    ├── MainScaffold.kt       NavigationBar: «Сенсор» / «Пристрій» / «Керування» / «Аналітика»
    ├── StepCounterScreen.kt  Compose UI + Canvas-індикатори
    ├── AiInsightsCard.kt     блок «AI Insights»: клас, ймовірності, inference, інтервал
    ├── DeviceScreen.kt       пошук, підключення, керування, термінал
    ├── ControlScreen.kt      лампа, ліхтарик, вібрація, авто-режим, статус SSE, журнал
    ├── AnalyticsScreen.kt    графік Vico, перемикачі серій, статистика БД, історія актуаторів
    └── theme/Theme.kt        Material 3 (dynamic color)
app/src/main/assets/          activity_classifier.tflite, labels.txt
app/schemas/                  експортована схема Room (JSON)
ml/                           model_config.json, generate_dataset.py, train.py, report.md
tools/emu_motion.py           симулятор акселерометра для емулятора
```
