# Crypto Alarm for Windows — v0.1

A native Windows 10/11 desktop edition of Crypto Alarm built with .NET 8 WinForms. No Binance API keys are required.

## Features

- Spot and USDⓈ-M Futures market watch, searchable USDT markets, sort by **24h volume** or **24h % change**, both directions
- Up/down alerts with user-selectable threshold (%) and rolling interval (minutes), unlimited rule list
- Binance trade WebSocket streams with automatic **REST polling and current 1-minute high/low candle fallback**
- Sound alarm (looping Windows WAV) + Windows tray popup on trigger
- Tray mode: close the window without stopping monitoring; explicit Exit stops it
- Auto-launch with Windows (optional) and optional start-monitoring on app launch
- Candlestick chart with drag, mousewheel fractional zoom, crosshair, Shift + drag percentage ruler, 'latest' shortcut
- Candle timeframes 1m / 5m / 15m / 1h / 4h / 1d / 1w
- Settings/rules stored locally at %APPDATA%/CryptoAlarm/settings.json

## Install and launch

Download the built CryptoAlarm-Windows-v0.1.zip from GitHub Actions. Extract it anywhere and double click **CryptoAlarm.exe**. The workflow publishes a 64-bit self-contained executable, so .NET does not need to be installed.

On first launch, open **Будильники**, add a rule (e.g. Futures / ETHUSDT / Падение / 0.5% / 1 min) and click **▶ Мониторинг**. Close the window to keep watching in the system tray. To exit fully, right-click the tray icon and choose **Выход**.

Important: If the exchange or network is inaccessible, live alerts are delayed or unavailable. REST fallback polls as often as the user-set interval, not tick-by-tick; no program can guarantee catching every intrasecond movement when WebSocket is blocked. Windows notifications/sound depend on your operating-system settings.

## Build from source

Requires Windows and the .NET 8 SDK:

    dotnet publish desktop/CryptoAlarm.Windows/CryptoAlarm.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true

The Windows and Android versions currently store rules **separately**. Cross-device synchronization is not part of v0.1. There is no trading/account access.

This project is for personal alerting and is not financial advice. Binance is a third-party service.
