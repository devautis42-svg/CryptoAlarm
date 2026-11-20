using System.Text.Json;

namespace CryptoAlarm.Windows;

public enum MarketKind { Spot, Futures }
public enum AlarmDirection { Drop, Rise }

public sealed class AlarmRule
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public MarketKind Market { get; set; } = MarketKind.Spot;
    public string Symbol { get; set; } = "ETHUSDT";
    public AlarmDirection Direction { get; set; } = AlarmDirection.Drop;
    public decimal ThresholdPercent { get; set; } = 0.5m;
    public int WindowMinutes { get; set; } = 1;
    public bool Enabled { get; set; } = true;
}

public sealed class AppSettings
{
    public List<AlarmRule> Rules { get; set; } = new();
    public int ScanSeconds { get; set; } = 2;
    public bool StartMonitoringOnLaunch { get; set; } = false;
    public bool MinimizeToTray { get; set; } = true;
    public string AlarmWavFile { get; set; } = "";
    public int AlarmSoundSeconds { get; set; } = 60;
    public MarketKind LastMarket { get; set; } = MarketKind.Spot;
}

public sealed record Ticker(string Symbol, decimal Price, decimal ChangePercent, decimal QuoteVolume);
public sealed record Candle(DateTimeOffset OpenTime, decimal Open, decimal High, decimal Low, decimal Close, decimal Volume);
public sealed record AlertEvent(AlarmRule Rule, decimal Price, decimal ChangePercent, DateTimeOffset Timestamp);

public static class LocalStore
{
    public static readonly string Folder = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CryptoAlarm");
    private static readonly string SettingsFile = Path.Combine(Folder, "settings.json");
    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(SettingsFile))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(SettingsFile)) ?? new AppSettings();
        }
        catch { /* corrupted or inaccessible settings: use defaults */ }
        return new AppSettings();
    }
    public static void Save(AppSettings settings)
    {
        Directory.CreateDirectory(Folder);
        File.WriteAllText(SettingsFile, JsonSerializer.Serialize(settings,
            new JsonSerializerOptions { WriteIndented = true }));
    }
}
