# Сторонние компоненты

| Компонент | Версия | Участие | Лицензия |
| --- | --- | --- | --- |
| [OpenAI Codex](https://github.com/openai/codex/tree/rust-v0.165.0) | 0.165.0 | Отдельно скачиваемый ARM64 runtime и app-server protocol | [Apache-2.0](https://github.com/openai/codex/blob/rust-v0.165.0/LICENSE) |
| [JSch / mwiede](https://github.com/mwiede/jsch) | 2.28.8 | Включается в DEX клиента для SSH-прототипа | BSD-style; полные notices в APK |
| [Xray-core](https://github.com/XTLS/Xray-core/releases/tag/v26.11.9) | 26.11.9 / 676eaf6 | Bundled Android ARM64 executable; app-local HTTP proxy | MPL-2.0; upstream LICENSE in APK |
| Android SDK tools | Platform 36 / Build Tools 36.0.0 | Внешние инструменты сборки, в репозиторий не включены | Условия Android SDK |
| [curl CA Extract / Mozilla roots](https://curl.se/docs/caextract.html) | Текущий CA bundle при установке | Отдельно скачиваемые стандартные trust roots для процесса Codex | Условия исходного набора сертификатов |

Полный текст notices JSch и его включённых компонентов: [android/assets/THIRD-PARTY-NOTICES.txt](android/assets/THIRD-PARTY-NOTICES.txt). Этот файл включён в опубликованный APK.

Название проекта описывает совместимость с Codex и не означает одобрение или авторство со стороны OpenAI.
