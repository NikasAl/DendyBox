# Публикация сборок в RuStore одним запуском

```
./scripts/rustore_upload.sh <flavor> [--submit] [--no-build] [--dry-run]
```

Скрипт собирает релиз, создаёт черновик новой версии, заливает APK,
иконку (`metadata/<flavor>/icon512.png`), скриншоты (`screenshots/`)
и тексты карточки (`title.txt`, `*_description.txt`, `release_notes.txt`,
`category.txt`, `age_rating.txt`), затем — с флагом `--submit` — отправляет
на модерацию. Тексты и картинки берутся из `metadata/<flavor>/`, никаких
полей в консоли заполнять не нужно.

Ограничение RuStore: API не создаёт **новое** приложение. Первую версию
каждой игры заведите вручную в консоли (https://console.rustore.ru):
карточка, APK, иконка, скриншоты, модерация. После публикации первой
версии все обновления удобно заливать этим скриптом.

## Шаг 1. Получить ключ API в консоли

1. Откройте https://console.rustore.ru под аккаунтом владельца
   (роли «Владелец компании» / «Администратор»; для физлиц вкладка
   называется «Разработчик»).
2. Вкладка **Компания** (или **Разработчик**) → левое меню → **API RuStore**.
3. **Создать ключ**: название (до 55 символов), выберите приложения,
   на которые действует ключ (или «Все приложения»), и методы —
   группа **«Загрузка и публикация приложений»**.
4. **Сгенерировать ключ** → приватный ключ показывается один раз —
   скопируйте его (строка вида `MIIEvgIBADANBgkqhkiG9w0BAQEF…`).
5. В таблице ключей посмотрите **ID ключа** — это `keyId`.

Приватный ключ привязан к аккаунту, а не к устройству: храните копию
в надёжном месте, как релизный keystore. Замена ключа — через меню
«⋮ → Заменить ключ», после замены обновите файл локально.

## Шаг 2. Настроить конфиг

```bash
mkdir -p ~/.config/rustore
cp scripts/rustore/config.example.json ~/.config/rustore/config.json
chmod 600 ~/.config/rustore/config.json
# сохраните приватный ключ из консоли:
nano ~/.config/rustore/private_key.pem      # вставьте строку MIIE… (или PEM)
nano ~/.config/rustore/config.json          # впишите keyId и пакеты
```

Конфиг:

| Поле | Что это |
|---|---|
| `keyId` | ID ключа из таблицы ключей консоли |
| `privateKey` | путь к файлу ключа (можно вставить саму base64-строку или PEM) |
| `defaults.publishType` | `MANUAL` — опубликовать руками после модерации (безопасно), `INSTANTLY` — сразу после модерации |
| `defaults.categories` | slug категории, если `category.txt` не распознан |
| `packages` | flavor → имя пакета (по умолчанию `com.dendybox.app.<flavor>`) |
| `overrides` | per-flavor переопределения: `{"<flavor>": {"categories": ["racing"], "publishType": "INSTANTLY"}}` |

Категории из `category.txt` распознаются автоматически: «Аркада» →
`arcade`, «Гонки» → `racing`, «Спорт» → `sports`, «Экшен» → `action`,
«Головоломки» → `puzzle` и т.д. (словарь в `rustore_api.py`).

## Шаг 3. Проверить и загрузить

```bash
python3 scripts/rustore/rustore_api.py check --live   # конфиг + авторизация
./scripts/rustore_upload.sh contra3in1 --dry-run      # план без загрузки
./scripts/rustore_upload.sh contra3in1                # залить черновик
./scripts/rustore_upload.sh contra3in1 --submit       # + на модерацию
```

Перед загрузкой поднимите версию игры (RuStore принимает только
`versionCode` выше активного):

```bash
./scripts/bump_version.sh contra3in1 1.1
```

Статус модерации: `python3 scripts/rustore/rustore_api.py status --flavor contra3in1`.

## Как это работает

* Авторизация: подпись SHA512withRSA строки `keyId + timestamp` приватным
  ключом → `POST /public/auth` → JWE-токен на 15 минут (заголовок
  `Public-Token`). Подпись делает `openssl`, python-зависимостей нет.
* Черновик версии создаётся заново при каждом запуске
  (`POST …/version` → `versionId`); предыдущий черновик RuStore удаляет сам.
* Скриншоты: до 10 штук, горизонтальные (LANDSCAPE), минимум 3 —
  иначе модерацию не пройти; снимаются `./scripts/screenshots.sh <flavor>`.
* `--dry-run` печатает план запросов, ничего не отправляя.

## Безопасность

`~/.config/rustore/` лежит вне репозитория; `scripts/rustore/config.local.json`
(альтернативное место конфига) добавлен в `.gitignore`. Ключ и конфиг
не коммитятся никогда.
