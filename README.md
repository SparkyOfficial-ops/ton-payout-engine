# ton-payout-engine

Шлюз массовых выплат TON. Пачку отправляет `CtonChainSender` через соседний `cton-sdk`.

Из SDK используются только:

- `com.cton.api.TonApiClient` — баланс и `sendBoc`
- `com.cton.contract.HighloadWallet` — сборка и подпись Highload Wallet v2
- `com.cton.contract.BulkTransfer` — один получатель внутри пачки
- `com.cton.sdk.Address` и `com.cton.sdk.Crypto.PrivateKey` — адрес и ключ Ed25519

`Cell`, `Boc` и `HashmapE` вызываются внутри `HighloadWallet`, отдельно их подключать не нужно. Нативная библиотека `cton-sdk-core` нужна в момент подписи. NFT, Jetton и обычные Wallet V3/V4 этому сервису не нужны.

## Запуск

```text
mvn -f ../cton-sdk-main/pom.xml install -DskipTests
mvn clean package
java -jar target/ton-payout-engine-0.1.0-SNAPSHOT.jar
```

Ключ API задаётся переменной `PAYOUT_API_KEY` (или `auth.apiKey` в `src/main/resources/application.properties`). Файл ключа кошелька лежит только на сервере, путь — `WALLET_KEY_PATH`. В репозиторий его не класть.

## API

Заголовок: `X-Api-Key`. Для выплаты обязателен `X-Idempotency-Key`: уникальный id операции на стороне бота. Повтор с тем же ключом и тем же телом не создаёт вторую пачку и возвращает текущий статус (`200`). Другое тело с тем же ключом отвечает `409`.

`POST /api/v1/payout`

```json
{
  "transfers": [
    {"to": "EQ...", "amount": "5", "comment": "vasya"},
    {"to": "EQ...", "amount": "2", "comment": "petya"},
    {"to": "EQ...", "amount": "10", "comment": "masha"}
  ]
}
```

`amount` — сумма в TON. `amountNano` по-прежнему принимается, если сумма уже в нанотонах. Ответ `202` содержит `batchId`.

Перед ответом переводы записываются в SQLite (`PAYOUT_DB_PATH`, по умолчанию `data/payout.db`) со статусом `queued`. После перезапуска такие переводы снова попадают в автобус. Статус `sending` означает, что отправка уже началась: эти строки после падения процесса сами не отправляются повторно.

Движок копит переводы `BATCH_WINDOW_MS` (по умолчанию 2500 мс) или до `BATCH_MAX_SIZE` (254) и отправляет их одной транзакцией Highload Wallet v2. Хэш из `sendBoc` означает только приём в мемпул: пачка становится `sent`. Через `CONFIRM_DELAY_MS` (по умолчанию 12 секунд) фоновый опрос смотрит `getTransactions` кошелька. Если хэш входящего сообщения есть в блоке, статус становится `confirmed`. Если после `CONFIRM_ATTEMPTS` проверок его нет, пачка становится `failed`.

Вебхук уходит на `sent` и ещё раз на `confirmed`:

```json
{"batchId":"...","status":"confirmed","count":3,"txHash":"...","error":null}
```

`GET /api/v1/status/{batchId}` показывает `queued`, `sending`, `paused`, `sent`, `confirmed` или `failed`.

Перед отправкой движок сравнивает баланс кошелька с суммой переводов плюс `FEE_RESERVE_NANO` (по умолчанию 0.05 TON). Если денег меньше, пачка становится `paused`, в сеть ничего не уходит, и на `ALERT_URL` отправляется алерт. Проверка повторяется через `PAUSE_RETRY_MS`.

Если пачка осталась в `sending` дольше `SENDING_STALE_MS` (5 минут) и в ней не осталось `queued` переводов, движок её не переотправляет. Хэш, который уже лежит в блоке, переводит пачку в `confirmed`. Хэш, которого в блоке нет, переводит её в `failed`, и бот повторяет выплату новым `X-Idempotency-Key`. Если хэш так и не записался, пачка тоже становится `failed`, но перед повтором нужно сверить историю кошелька: сообщение могло успеть попасть в блок. `POST /api/v1/recovery` запускает ту же проверку и не трогает `sending` младше пяти минут.

`GET /api/v1/balance`

`GET /health` без ключа.

Нужны `WALLET_ADDRESS` и файл ключа (`WALLET_KEY_PATH`: 32 байта, 64 hex-символа, base64 или мнемоника). Без них пачка получает статус `failed` и в сеть не уходит. `txHash` — это hash внешнего сообщения из ответа TON Center `sendBoc`. Подтверждение сравнивает его только с `in_msg.hash` или `in_msg.body_hash`. `transaction_id.hash` — это хэш самой транзакции, он не используется. В лог пишется сырой JSON первой транзакции из `getTransactions` и обе строки `in_msg`, чтобы их можно было сверить с хэшем `cton-sdk` посимвольно.
