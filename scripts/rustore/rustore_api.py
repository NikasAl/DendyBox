#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RuStore Publish API — загрузка сборок DendyBox в магазин одним запуском.

Документация: https://www.rustore.ru/help/work-with-rustore-api/
(раздел «Загрузка и публикация приложений»)

Команды:
  check  [--live]                 — проверить конфиг/ключ (без сети;
                                    --live — с реальным запросом токена)
  status  --flavor F              — статусы версий приложения
  upload  --flavor F [--apk путь] — создать черновик версии, залить APK,
                                    иконку и скриншоты из metadata/<flavor>/
                                    (--submit — сразу отправить на модерацию)

Конфиг — первый найденный файл:
  $RUSTORE_CONFIG
  ~/.config/rustore/config.json
  scripts/rustore/config.local.json      (в git не попадает)
Шаблон с комментариями: scripts/rustore/config.example.json

Зависимости: python3 (только stdlib) + утилита openssl.

Важно: API не создаёт НОВОЕ приложение — первую версию каждого приложения
заведите вручную в https://console.rustore.ru (карточка + APK), после этого
этот скрипт автоматизирует все последующие обновления.
"""

import argparse
import base64
import datetime
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid

BASE = "https://public-api.rustore.ru"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
META_DIR = os.path.join(ROOT, "metadata")
GAMES_JSON = os.path.join(ROOT, "roms", "games.json")

AGE_VALUES = ("0+", "6+", "12+", "16+", "18+")

# «Игры / Аркада» из metadata/<flavor>/category.txt → slug категории RuStore.
# Если категория не найдена в словаре — она просто не передаётся (RuStore
# возьмёт её из активной версии), а в конфиге можно задать slug напрямую.
CATEGORY_MAP = {
    "аркада": "arcade",
    "аркады": "arcade",
    "экшен": "action",
    "action": "action",
    "гонки": "racing",
    "спорт": "sports",
    "головоломки": "puzzle",
    "пазлы": "puzzle",
    "настольные": "board",
    "казуальные": "casual",
    "приключения": "adventure",
    "rpg": "rpg",
    "стратегии": "strategy",
    "симуляторы": "simulation",
}


class ApiError(Exception):
    """Ошибка вызова API или логическая ошибка сценария."""

    def __init__(self, message, code=None):
        super().__init__(message)
        self.code = code


# ============================================================================
# Конфиг
# ============================================================================

def config_paths():
    paths = []
    if os.environ.get("RUSTORE_CONFIG"):
        paths.append(os.path.expanduser(os.environ["RUSTORE_CONFIG"]))
    paths.append(os.path.expanduser("~/.config/rustore/config.json"))
    paths.append(os.path.join(ROOT, "scripts", "rustore", "config.local.json"))
    return paths


def load_config():
    for p in config_paths():
        if os.path.isfile(p):
            try:
                with open(p, encoding="utf-8") as f:
                    cfg = json.load(f)
            except Exception as e:
                raise ApiError(f"Конфиг {p} не читается: {e}")
            cfg["__path"] = p
            return cfg
    raise ApiError(
        "Конфиг RuStore не найден. Создайте ~/.config/rustore/config.json по\n"
        "шаблону scripts/rustore/config.example.json (см. scripts/rustore/README.md)"
    )


def resolve_package(cfg, flavor):
    pkgs = cfg.get("packages") or {}
    if flavor in pkgs:
        val = pkgs[flavor]
        if not isinstance(val, str) or not val.strip():
            raise ApiError(
                f"packages.{flavor} должен быть строкой — именем пакета\n"
                f"(переопределения категорий/публикации задаются в блоке overrides)")
        return val.strip()
    prefix = cfg.get("packageNamePrefix")
    if prefix:
        return prefix + flavor
    if cfg.get("packageName"):
        return cfg["packageName"]
    # DendyBox: applicationId каждого flavor — com.dendybox.app.<flavor>
    return "com.dendybox.app." + flavor


def overrides(cfg, flavor):
    ov = (cfg.get("overrides") or {}).get(flavor) or {}
    return ov if isinstance(ov, dict) else {}


def private_key_bytes(cfg):
    """Ключ из конфига: путь к файлу, инлайн PEM или инлайн base64 (DER)."""
    val = cfg.get("privateKey") or cfg.get("privateKeyPath")
    if not val:
        raise ApiError(
            "В конфиге нет поля privateKey (путь к файлу ключа либо сам ключ:\n"
            "PEM или base64-строка из RuStore Консоли)"
        )
    val = val.strip()
    if "-----BEGIN" in val:
        return val.encode()
    path = os.path.expanduser(val)
    if os.path.isfile(path):
        with open(path, "rb") as f:
            return f.read().strip()
    return val.encode("utf-8")  # инлайн base64-строка (декодирует normalize_key)


def normalize_key(raw):
    """→ (байты для openssl, keyform). Консоль RuStore отдаёт base64(PKCS8 DER)."""
    if b"-----BEGIN" in raw:
        return raw, "PEM"
    try:
        return base64.b64decode(raw.strip(), validate=True), "DER"
    except Exception:
        raise ApiError(
            "Ключ не распознан: ожидается PEM (-----BEGIN …) или base64-строка\n"
            "из RuStore Консоли (начинается с MIIE…)"
        )


# ============================================================================
# Подпись и авторизация
# ============================================================================

def openssl_sign(key_bytes, message):
    """SHA512withRSA по сообщению keyId+timestamp, результат — base64."""
    pem_or_der, keyform = normalize_key(key_bytes)
    with tempfile.NamedTemporaryFile(delete=False, suffix=".key") as f:
        f.write(pem_or_der)
        keyfile = f.name
    try:
        cmd = ["openssl", "dgst", "-sha512", "-sign", keyfile]
        if keyform == "DER":
            cmd.insert(2, "-keyform")
            cmd.insert(3, "DER")
        p = subprocess.run(cmd, input=message, capture_output=True)
        if p.returncode != 0:
            raise ApiError(
                "openssl не смог подписать сообщение ключом:\n"
                + p.stderr.decode("utf-8", "replace").strip()
            )
        return base64.b64encode(p.stdout).decode()
    finally:
        os.unlink(keyfile)


def generate_auth_body(cfg):
    key_id = str(cfg.get("keyId") or "").strip()
    if not key_id:
        raise ApiError("В конфиге нет поля keyId (ID ключа из RuStore Консоли)")
    ts = datetime.datetime.now().astimezone().isoformat(timespec="milliseconds")
    signature = openssl_sign(private_key_bytes(cfg), (key_id + ts).encode("utf-8"))
    return {"keyId": key_id, "timestamp": ts, "signature": signature}


# ============================================================================
# HTTP
# ============================================================================

def api(method, path, token=None, json_body=None, data=None, content_type=None,
        timeout=180):
    headers = {"Accept": "application/json"}
    if token:
        headers["Public-Token"] = token
    body = None
    if json_body is not None:
        body = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    elif data is not None:
        body = data
        if content_type:
            headers["Content-Type"] = content_type
    req = urllib.request.Request(BASE + path, data=body, headers=headers,
                                 method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as e:
        raw = e.read()
        detail = raw[:400].decode("utf-8", "replace")
        try:
            j = json.loads(detail)
            detail = j.get("message") or detail
        except Exception:
            pass
        raise ApiError(f"HTTP {e.code}: {method} {path}\n  {detail}", e.code)
    except urllib.error.URLError as e:
        raise ApiError(f"Нет связи с {BASE} ({e.reason})")
    if not raw:
        return {}
    try:
        return json.loads(raw.decode("utf-8", "replace"))
    except Exception:
        return {}


def check_code(resp, what):
    """Единая проверка {code: OK|error, message}."""
    if not isinstance(resp, dict):
        return resp
    code = resp.get("code")
    if code and code != "OK":
        raise ApiError(f"{what}: {resp.get('message') or code}")
    return resp


def multipart(field, filename, content, ctype):
    boundary = "----DendyBox" + uuid.uuid4().hex
    pre = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        f"Content-Type: {ctype}\r\n\r\n"
    ).encode("utf-8")
    post = f"\r\n--{boundary}--\r\n".encode("utf-8")
    return pre + content + post, f"multipart/form-data; boundary={boundary}"


def auth(cfg):
    body = generate_auth_body(cfg)
    resp = check_code(api("POST", "/public/auth", json_body=body),
                      "Не удалось получить токен")
    token = (resp.get("body") or {}).get("jwe")
    if not token:
        raise ApiError("Ответ /public/auth без токена (body.jwe пуст)")
    return token


# ============================================================================
# Метаданные проекта
# ============================================================================

def read_text(flavor, name):
    p = os.path.join(META_DIR, flavor, name)
    if not os.path.isfile(p):
        return None
    with open(p, encoding="utf-8") as f:
        val = f.read().strip()
    return val or None


def resolve_categories(cfg, flavor):
    own = overrides(cfg, flavor)
    if own.get("categories"):
        return own["categories"]
    defaults = cfg.get("defaults") or {}
    if defaults.get("categories"):
        return defaults["categories"]
    cat = read_text(flavor, "category.txt")
    if cat:
        for part in cat.replace(";", "/").split("/"):
            slug = CATEGORY_MAP.get(part.strip().lower())
            if slug:
                return [slug]
    return None


def collect_metadata(cfg, flavor):
    """→ dict полей черновика из metadata/<flavor>/ (отсутствующие — пропускаем)."""
    md = {}

    title = read_text(flavor, "title.txt")
    if title:
        if len(title) > 50:
            raise ApiError(f"title.txt длиннее 50 символов ({len(title)}) — лимит appName")
        md["appName"] = title

    short = read_text(flavor, "short_description.txt")
    if short:
        if len(short) > 80:
            raise ApiError(f"short_description.txt длиннее 80 символов ({len(short)})")
        md["shortDescription"] = short

    full = read_text(flavor, "full_description.txt")
    if full:
        if len(full) > 4000:
            raise ApiError(f"full_description.txt длиннее 4000 символов ({len(full)})")
        md["fullDescription"] = full

    notes = read_text(flavor, "release_notes.txt")
    if notes:
        if len(notes) > 5000:
            raise ApiError(f"release_notes.txt длиннее 5000 символов ({len(notes)})")
        md["whatsNew"] = notes

    age = read_text(flavor, "age_rating.txt")
    if age:
        import re as _re
        m = _re.search(r"(0\+|6\+|12\+|16\+|18\+)", age)
        if not m:
            raise ApiError(f"age_rating.txt: «{age}» — нет значения из {AGE_VALUES}")
        md["ageLegal"] = m.group(1)

    cats = resolve_categories(cfg, flavor)
    if cats:
        md["categories"] = cats

    md["appType"] = "GAMES"
    return md


def games_json_version(flavor):
    """(versionCode, versionName) flavor'а из roms/games.json — для проверки,
    что версия выросла относительно активной в магазине."""
    if not os.path.isfile(GAMES_JSON):
        return None, None
    try:
        with open(GAMES_JSON, encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        return None, None
    for fname, meta in data.items():
        meta = meta or {}
        # санитайзер как в app/build.gradle.kts (sanitizeFlavorName)
        fl = str(meta.get("flavor") or fname.rsplit(".", 1)[0].lower())
        fl = "".join(c for c in fl.lower() if c.isalnum())
        if not fl or not fl[0].isalpha():
            fl = "game" + fl
        if fl == flavor:
            code = meta.get("versionCode")
            name = meta.get("versionName")
            return (int(code) if isinstance(code, (int, float)) else None, name)
    return None, None


def find_apk(flavor):
    out = os.path.join(ROOT, "app", "build", "outputs", "apk", flavor, "release")
    best = None
    for root, _dirs, files in os.walk(out):
        for f in files:
            if f.endswith(".apk"):
                p = os.path.join(root, f)
                if best is None or os.path.getmtime(p) > os.path.getmtime(best):
                    best = p
    return best


def icon_path(flavor):
    for name in ("icon512.png", "icon.png"):
        p = os.path.join(META_DIR, flavor, name)
        if os.path.isfile(p):
            return p
    return None


def screenshot_paths(flavor):
    d = os.path.join(META_DIR, flavor, "screenshots")
    if not os.path.isdir(d):
        return []
    exts = (".png", ".jpg", ".jpeg")
    files = sorted(f for f in os.listdir(d)
                   if f.lower().endswith(exts) and not f.startswith("."))
    return [os.path.join(d, f) for f in files]


# ============================================================================
# Команды
# ============================================================================

def cmd_check(args):
    cfg = load_config()
    print(f"Конфиг: {cfg['__path']}")
    if not cfg.get("keyId"):
        raise ApiError("keyId не заполнен")
    key = private_key_bytes(cfg)
    form = "PEM" if b"-----BEGIN" in key else "base64/DER"
    print(f"keyId: {cfg['keyId']}")
    print(f"Приватный ключ: {len(key)} байт, формат {form}")

    if not shutil.which("openssl"):
        raise ApiError("Утилита openssl не найдена в PATH")

    # Самопроверка: подпись + проверка открытым ключом
    msg = b"dendybox-selftest"
    pem_or_der, keyform = normalize_key(key)
    with tempfile.TemporaryDirectory() as td:
        kf = os.path.join(td, "k.pem")
        with open(kf, "wb") as f:
            f.write(pem_or_der)
        sign_cmd = ["openssl", "dgst", "-sha512", "-sign", kf]
        if keyform == "DER":
            sign_cmd[2:2] = ["-keyform", "DER"]
        sig = subprocess.run(sign_cmd, input=msg, capture_output=True)
        if sig.returncode != 0:
            raise ApiError("Ключ не подходит для подписи:\n"
                           + sig.stderr.decode("utf-8", "replace").strip())
        pub = subprocess.run(["openssl", "pkey", "-in", kf, "-pubout"],
                             input=b"", capture_output=True)
        if pub.returncode != 0:
            raise ApiError("Не удалось извлечь публичный ключ:\n"
                           + pub.stderr.decode("utf-8", "replace").strip())
        pubf = os.path.join(td, "pub.pem")
        with open(pubf, "wb") as f:
            f.write(pub.stdout)
        sigf = os.path.join(td, "sig.bin")
        with open(sigf, "wb") as f:
            f.write(sig.stdout)
        ver = subprocess.run(["openssl", "dgst", "-sha512", "-verify", pubf,
                              "-signature", sigf], input=msg, capture_output=True)
        if ver.returncode != 0:
            raise ApiError("Подпись не прошла самопроверку")
    print("Подпись SHA512withRSA: OK (самопроверка пройдена)")

    pkg = resolve_package(cfg, args.flavor) if args.flavor else None
    if pkg:
        print(f"Пакет для flavor «{args.flavor}»: {pkg}")

    if args.live:
        token = auth(cfg)
        print("Авторизация в RuStore: OK (JWE-токен получен, ttl 900 с)")
    print("Конфиг готов к работе.")


def list_versions(pkg, token):
    resp = check_code(
        api("GET", f"/public/v1/application/{pkg}/version?page=0&size=100",
            token=token), "Список версий")
    body = resp.get("body") or {}
    return body.get("content") or []


PUBLISHED_LIKE = {"ACTIVE", "PARTIAL_ACTIVE", "PUBLISHED", "READY_FOR_PUBLICATION",
                  "PREVIOUS_ACTIVE"}


def require_active_app(cfg, pkg, token, dry):
    """API работает только для приложений с активной версией."""
    if dry:
        print("[dry-run] проверка активной версии пропущена")
        return None, None
    apps = check_code(api("GET", f"/public/v1/application?appPackage={pkg}",
                          token=token), "Список приложений")
    content = (apps.get("body") or {}).get("content") or []
    if not any(a.get("packageName") == pkg for a in content):
        raise ApiError(
            f"Приложение {pkg} не найдено в аккаунте.\n"
            "Создайте карточку и первую версию вручную в https://console.rustore.ru\n"
            "(API автоматизирует только обновления существующих приложений).")
    versions = list_versions(pkg, token)
    active = [v for v in versions if v.get("versionStatus") in PUBLISHED_LIKE]
    if not active:
        raise ApiError(
            f"У {pkg} нет активной версии — сначала опубликуйте первую версию\n"
            "через веб-консоль (https://console.rustore.ru), затем используйте API.")
    drafts = [v for v in versions if v.get("versionStatus") == "DRAFT"]
    if drafts:
        print(f"Внимание: у {pkg} есть черновик версии {drafts[0].get('versionId')} —\n"
              "создание нового черновика через API автоматически удалит его.")
    return max(v.get("versionCode") or 0 for v in active), versions


def cmd_status(args):
    cfg = load_config()
    pkg = resolve_package(cfg, args.flavor)
    token = auth(cfg)
    versions = list_versions(pkg, token)
    if not versions:
        print(f"У {pkg} версий нет (приложение может быть не создано).")
        return
    print(f"Версии {pkg}:")
    for v in versions:
        print(f"  versionId={v.get('versionId')}  {v.get('versionStatus'):<22} "
              f"code={v.get('versionCode')}  {v.get('versionName')}  "
              f"{v.get('publishType')}  "
              f"whatsNew: {(v.get('whatsNew') or '')[:40]}")


def cmd_upload(args):
    cfg = load_config()
    flavor = args.flavor
    pkg = resolve_package(cfg, flavor)
    dry = args.dry_run

    # --- APK ---
    apk = args.apk or find_apk(flavor)
    if apk and not os.path.isfile(apk):
        apk = None
    if apk:
        apk_size = os.path.getsize(apk)
        print(f"APK: {apk} ({apk_size / (1 << 20):.1f} МиБ)")
    elif not dry:
        raise ApiError(
            f"APK не найден. Соберите его: ./scripts/rustore_upload.sh {flavor}\n"
            "(или ./scripts/build_release.sh " + flavor + ") — либо передайте путь\n"
            "явно: --apk app/build/outputs/apk/" + flavor + "/release/….apk")

    # --- версия из games.json против активной в магазине ---
    new_code, new_name = games_json_version(flavor)
    if dry:
        active_code = None
    else:
        token = auth(cfg)
        active_code, _versions = require_active_app(cfg, pkg, token, dry)
    if active_code and new_code and int(new_code) <= int(active_code):
        raise ApiError(
            f"versionCode {new_code} не выше активной версии в магазине ({active_code}).\n"
            f"Поднимите версию: ./scripts/bump_version.sh {flavor} <новая_версия>\n"
            "и пересоберите APK.")

    # --- метаданные ---
    md = collect_metadata(cfg, flavor)
    ptype = (args.publish_type
             or overrides(cfg, flavor).get("publishType")
             or (cfg.get("defaults") or {}).get("publishType")
             or "MANUAL")
    md["publishType"] = ptype
    print("Метаданные черновика:")
    for k, v in md.items():
        text = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)
        print(f"  {k}: {text[:70]}{'…' if len(text) > 70 else ''}")

    # --- картинки ---
    icon = icon_path(flavor)
    shots = screenshot_paths(flavor)
    print(f"Иконка: {icon or '— (не найдена)'}")
    print(f"Скриншоты: {len(shots)} шт. (LANDSCAPE)")
    if len(shots) < 3:
        print("Внимание: RuStore требует минимум 3 скриншота для публикации —\n"
              f"снимите: ./scripts/screenshots.sh {flavor} 15 20")

    if dry:
        step = 2
        plan = ["1) POST /public/auth → токен",
                f"2) POST /public/v1/application/{pkg}/version → versionId"]
        step += 1
        apk_label = os.path.basename(apk) if apk else "(APK появится при сборке)"
        plan.append(f"{step}) POST .../version/{{id}}/apk?isMainApk=true  ← {apk_label}")
        if icon:
            step += 1
            plan.append(f"{step}) POST .../version/{{id}}/image/icon  ← {os.path.basename(icon)}")
        for i, s in enumerate(shots[:10]):
            step += 1
            plan.append(f"{step}) POST .../image/screenshot/LANDSCAPE/{i} ← {os.path.basename(s)}")
        step += 1
        plan.append(f"{step}) POST .../version/{{id}}/commit"
                    + ("  (--submit)" if args.submit else "  (пропущен без --submit)"))
        print("[dry-run] сетевые вызовы не выполняются; план:", *plan, sep="\n   ")
        print("[dry-run] Готово.")
        return

    # --- 1. черновик версии ---
    resp = check_code(
        api("POST", f"/public/v1/application/{pkg}/version", token=token,
            json_body=md), "Создание черновика версии")
    version_id = resp.get("body")
    if not version_id:
        raise ApiError(f"RuStore не вернул versionId: {resp}")
    print(f"[1/4] Черновик версии создан: versionId={version_id}")

    # --- 2. APK ---
    with open(apk, "rb") as f:
        content = f.read()
    body, ctype = multipart("file", os.path.basename(apk), content,
                            "application/vnd.android.package-archive")
    check_code(api("POST",
                   f"/public/v1/application/{pkg}/version/{version_id}"
                   f"/apk?isMainApk=true&servicesType=Unknown",
                   token=token, data=body, content_type=ctype, timeout=1800),
               "Загрузка APK")
    print(f"[2/4] APK загружен ({os.path.getsize(apk) / (1 << 20):.1f} МиБ)")

    # --- 3. иконка + скриншоты ---
    done_img = 0
    if icon:
        with open(icon, "rb") as f:
            ib, ict = multipart("file", os.path.basename(icon), f.read(),
                                "image/png")
        check_code(api("POST",
                       f"/public/v1/application/{pkg}/version/{version_id}/image/icon",
                       token=token, data=ib, content_type=ict), "Загрузка иконки")
        done_img += 1
    for i, sp in enumerate(shots[:10]):
        with open(sp, "rb") as f:
            sb, sct = multipart("file", os.path.basename(sp), f.read(),
                                "image/png" if sp.lower().endswith(".png")
                                else "image/jpeg")
        check_code(api("POST",
                       f"/public/v1/application/{pkg}/version/{version_id}"
                       f"/image/screenshot/LANDSCAPE/{i}",
                       token=token, data=sb, content_type=sct),
                   f"Скриншот {i}")
        done_img += 1
    print(f"[3/4] Изображения загружены: {done_img}")

    # --- 4. модерация ---
    if args.submit:
        check_code(api("POST",
                       f"/public/v1/application/{pkg}/version/{version_id}/commit",
                       token=token), "Отправка на модерацию")
        print("[4/4] Отправлено на модерацию (статус: TAKEN_FOR_MODERATION)")
        print(f"Следить: ./scripts/rustore_upload.sh status {flavor}")
    else:
        print("[4/4] Не отправлено на модерацию (--submit не указан)")
    print("")
    print(f"Готово. Черновик versionId={version_id} доступен в https://console.rustore.ru")


def main():
    parser = argparse.ArgumentParser(
        description="Загрузка сборок DendyBox в RuStore (см. scripts/rustore/README.md)")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("check", help="проверить конфиг и ключ")
    p.add_argument("--flavor", help="заодно показать packageName этого flavor'а")
    p.add_argument("--live", action="store_true", help="выполнить реальный запрос токена")
    p.set_defaults(fn=cmd_check)

    p = sub.add_parser("status", help="статусы версий приложения")
    p.add_argument("--flavor", required=True)
    p.set_defaults(fn=cmd_status)

    p = sub.add_parser("upload", help="черновик + APK + картинки (+ модерация)")
    p.add_argument("--flavor", required=True)
    p.add_argument("--apk", help="путь к APK (по умолчанию — свежайший release)")
    p.add_argument("--submit", action="store_true", help="отправить на модерацию")
    p.add_argument("--publish-type", choices=("MANUAL", "INSTANTLY", "DELAYED"),
                   help="тип публикации (по умолчанию MANUAL)")
    p.add_argument("--dry-run", action="store_true", help="показать план без сети")
    p.set_defaults(fn=cmd_upload)

    args = parser.parse_args()
    try:
        args.fn(args)
    except ApiError as e:
        print(f"ОШИБКА: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
