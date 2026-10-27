# ton-payout-engine

Минимальный каркас шлюза массовых выплат TON. Очередь и HTTP уже есть. Подпись и отправка в сеть ещё не подключены: следующим шагом сюда стыкуется `com.cton.contract.HighloadWallet.sendBulkTransfer` из соседнего `cton-sdk`.

## Запуск

```text
mvn clean package
java -jar target/ton-payout-engine-0.1.0-SNAPSHOT.jar
```

Ключ API задаётся переменной `PAYOUT_API_KEY` (или `auth.apiKey` в `src/main/resources/application.properties`). Файл ключа кошелька лежит только на сервере, путь — `WALLET_KEY_PATH`. В репозиторий его не класть.

## API

Заголовок: `X-Api-Key`.

`POST /api/v1/payout`

```json
{
  "transfers": [
    {"to": "EQ...", "amountNano": "1000000000", "comment": "reward"}
  ]
}
```

`GET /api/v1/status/{batchId}`

`GET /api/v1/balance`

`GET /health` без ключа.

Пока сеть не подключена, пачка доходит до статуса `held`: она собрана (до 254 получателей), но в блокчейн не уходит.
