from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
service_path = root / "app/src/main/java/com/cryptoalarm/app/MarketMonitorService.kt"
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

service = service_path.read_text(encoding="utf-8")

# Re-arm a system watchdog whenever monitoring is active. START_STICKY alone
# is not enough on aggressive Android/OEM battery managers that kill the whole
# process overnight.
start_anchor = '''        RuleStore.setMonitoring(this, true)
        acquireWakeLock()
        prepareAlarmPlayer()
'''
if start_anchor not in service:
    raise RuntimeError("Could not locate startMonitoring anchor")
service = service.replace(
    start_anchor,
    '''        RuleStore.setMonitoring(this, true)
        acquireWakeLock()
        prepareAlarmPlayer()
        ServiceWatchdog.schedule(this)
''',
    1,
)

stop_anchor = '''        connectedMarkets.clear()
        reconnectPending.clear()
        RuleStore.setMonitoring(this, false)
'''
if stop_anchor not in service:
    raise RuntimeError("Could not locate stopMonitoring anchor")
service = service.replace(
    stop_anchor,
    '''        connectedMarkets.clear()
        reconnectPending.clear()
        ServiceWatchdog.cancel(this)
        RuleStore.setMonitoring(this, false)
''',
    1,
)

on_destroy_anchor = '''    override fun onDestroy() {
        configJob?.cancel()
'''
if on_destroy_anchor not in service:
    raise RuntimeError("Could not locate onDestroy")
service = service.replace(
    on_destroy_anchor,
    '''    override fun onTaskRemoved(rootIntent: Intent?) {
        if (RuleStore.isMonitoring(this)) {
            ServiceWatchdog.schedule(this, 15_000L)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (RuleStore.isMonitoring(this)) {
            ServiceWatchdog.schedule(this, 15_000L)
        }
        configJob?.cancel()
''',
    1,
)

service_path.write_text(service, encoding="utf-8")

app = app_path.read_text(encoding="utf-8")

battery_action = '''        item { VAction("🔋", "Оптимизация батареи", "Разрешить работу мониторинга в фоне") { runCatching { activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${activity.packageName}") }) }.onFailure { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } } }
'''
if battery_action not in app:
    raise RuntimeError("Could not locate battery settings action")
if "Надёжный перезапуск" not in app:
    app = app.replace(
        battery_action,
        battery_action + '''        item { VAction("⏰", "Надёжный перезапуск", "Разрешить точные проверки watchdog ночью") { runCatching { activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:${activity.packageName}") }) } } }
''',
        1,
    )

app = app.replace(
    'Версия 1.2.5 • исправлен движок будильников',
    'Версия 1.2.6 • ночной watchdog'
)
app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 20', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.2.6"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.2.6 night watchdog")
