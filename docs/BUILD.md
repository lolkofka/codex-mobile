# Сборка и проверки

Сборка не требует Android Studio или Gradle. Нужны Python 3.10+, JDK 21 и Android SDK с platform 36 и Build Tools 36.0.0.

## Android SDK

Установите [Android Command Line Tools](https://developer.android.com/studio#command-tools) и пакеты:

```console
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Укажите путь к SDK через `ANDROID_HOME` / `ANDROID_SDK_ROOT` или параметр `--sdk`. `java`, `javac`, `keytool` должны быть доступны через `JAVA_HOME` или `PATH`.

```console
python tools/build.py --sdk /path/to/android-sdk
```

Скрипт скачивает закреплённый официальный Xray ARM64 (проверяет SHA-256 архива и бинарника), а также JSch 2.28.8 из Maven Central, проверяет его закреплённый SHA-256, компилирует Java, собирает DEX и ресурсы, выравнивает и подписывает APK. Результат находится в `dist/`; хеш — в `dist/SHA256SUMS`. Инструменты и кэши находятся в `.local/` и исключены из Git.

## Подпись и обновления

При обычной локальной сборке создаётся личный development-ключ в `.local/private-signing/`. Сохраните его отдельно, если планируете обновлять свою сборку с сохранением данных. Этот ключ не совпадает с ключом опубликованного APK; CI-сборки также не являются обновлением подписанного релиза.

Для сборки с собственным существующим ключом:

```console
python tools/build.py --sdk /path/to/sdk --keystore /private/release.p12 --password-file /private/password.txt --alias your-alias
```

Ключ и файл пароля не включаются в репозиторий. Пароль передаётся `apksigner` через файл, не через публичную командную строку. Приватный ключ опубликованной сборки не распространяется.

## Проверка исходников

```console
python tools/check_project.py
```

Проверяются версия, манифест, запрет экспортированного файлового провайдера, состав публичного дерева, JavaScript и Python. Для проверки JavaScript нужен Node.js.

## Проверки интерфейса

```console
python tests/run_ui_tests.py --browser /path/to/chrome-or-edge
```

Тест запускает отдельный headless-профиль браузера и HTTP-сервер только на `127.0.0.1`. Данные синтетические; аккаунт и фото владельца не используются. Проверяются выбор вложений, сообщение только с фото, ссылки, фильтрация HTML, очередь/уточнение и кнопка системных настроек уведомлений. Результат и снимок сохраняются в `.local/ui-tests/`.

## Native instrumentation

Проверки входа и сетевого моста без телефона:

```console
python tests/run_host_tests.py
```

На телефоне дополнительно доступен `-e authOnly true`: он обновляет токен через официальный app-server и делает синтетический запрос без учётных данных к token endpoint. Учётные данные и содержимое `auth.json` тест не выводит.

После обычной сборки подготовьте тестовый APK, подписанный тем же ключом:

```console
python tools/build_native_tests.py --sdk /path/to/android-sdk
adb -s SERIAL install -r dist/Codex-Mobile-0.6.7-copy.apk
adb -s SERIAL install -r dist/Codex-Mobile-tests.apk
adb -s SERIAL shell am instrument -w -r -e media true local.avr.codex.copy.tests/local.avr.codex.copy.MobileFeatureTests
adb -s SERIAL uninstall local.avr.codex.copy.tests
```

Не запускайте instrumentation во время пользовательской задачи: Android перезапускает процесс тестируемого приложения. Основные протокольные тесты используют отдельное тестовое хранилище. Проверки media создают и удаляют собственные тестовые файлы. Для проверки доставки уведомления отдельно разрешите `POST_NOTIFICATIONS` приложению и передайте `-e notifyOnly true` вместо `-e media true`.

Для опубликованного release APK тестовый APK тоже должен иметь release-подпись: самостоятельно собранный тестовый пакет с другим сертификатом к нему не подключится.

## Root helper bundle личной копии

```console
python tools/build_phone_tools.py --sdk /path/to/sdk
```

Результат — `dist/phone-tools/`: DEX и только статические скрипты из `phone-tools/`. Никакие рабочие отчёты, ключи, сертификаты, app data и сохранённые профили не включаются. Сборка и CI не запускают helpers и не изменяют телефон. Пути/UID текущей личной копии описаны в INSTALL.md.
