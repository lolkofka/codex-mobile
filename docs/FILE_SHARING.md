# File actions

Tap a local file link in an assistant response (including sandbox: and file: links), an attachment or generated image. A native dialog offers Отправить…, Открыть and Отмена. Отправить… opens Android’s Sharesheet; the owner can choose Telegram and then a recipient. No app or recipient is preselected, and no message is sent automatically.

The existing app-private shared copy is sent as ACTION_SEND + EXTRA_STREAM with its specific MIME type and filename. ClipData and temporary read-only URI grants are present on both the target and chooser intents. The non-exported SharedFileProvider exposes only the selected shared URI; no root path or text surrogate is sent. Opening files still uses ACTION_VIEW. Directories retain the existing file browser; HTTPS links retain browser handling.
