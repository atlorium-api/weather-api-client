// Клиент API погоды Atlorium — текущая погода по географическим координатам.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//     dotnet run
//     dotnet run 59.9343 30.3351
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.

using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Serialization;

// Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальными
// данными) — чтобы можно было встроить и протестировать интеграцию до оплаты.
// Ответы привязаны к округлённым координатам: одна и та же точка всегда даёт одну
// и ту же «погоду», поэтому на них можно писать стабильные тесты.
const string SandboxKey = "ak_sandbox_demo_mockdata_v1";

// Числа печатаем инвариантно. Иначе под ru-RU разделитель дробной части — запятая,
// и температура вышла бы как «-14,0», а координаты — как «55,7558»: вывод разошёлся
// бы с CI и с README на ровном месте.
CultureInfo.DefaultThreadCurrentCulture = CultureInfo.InvariantCulture;

var apiKey = Environment.GetEnvironmentVariable("ATLORIUM_API_KEY") ?? SandboxKey;
var baseUrl = Environment.GetEnvironmentVariable("ATLORIUM_BASE_URL") ?? "https://atlorium.com";

using var http = new HttpClient
{
    BaseAddress = new Uri(baseUrl),
    Timeout = TimeSpan.FromSeconds(30),
};
http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

var client = new WeatherClient(http);

if (apiKey == SandboxKey)
{
    Console.WriteLine("Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n");
}

// Дефолт — Москва. Координаты передаются двумя аргументами: dotnet run 59.93 30.33
// (именно `dotnet run`, без --nologo: флаг улетит в args[0] и сломает разбор).
var latitude = args.Length > 0 ? double.Parse(args[0], CultureInfo.InvariantCulture) : 55.7558;
var longitude = args.Length > 1 ? double.Parse(args[1], CultureInfo.InvariantCulture) : 37.6173;

Weather weather;
try
{
    weather = await client.GetWeatherAsync(latitude, longitude);
}
catch (AtloriumException error)
{
    Console.Error.WriteLine($"Ошибка: {error.Message}");
    return 1;
}

var sky = weather.Conditions.FirstOrDefault();

Console.WriteLine($"Погода по координатам {latitude}, {longitude}");
Console.WriteLine($"  Точка:        {weather.Name} ({weather.Sys?.Country}), " +
                  $"coord из ответа: {weather.Coord?.Lat}, {weather.Coord?.Lon}");
Console.WriteLine($"  Замер:        {Format.LocalTime(weather.Dt, weather.Timezone)}");
Console.WriteLine($"  Небо:         {sky?.Description} ({sky?.Main})");
Console.WriteLine($"  Температура:  {weather.Main.Temp:F1} °C, " +
                  $"ощущается как {weather.Main.FeelsLike:F1} °C");

var gust = weather.Wind.Gust is { } value ? $", порывы до {value:F1} м/с" : "";
Console.WriteLine($"  Ветер:        {weather.Wind.Speed:F1} м/с{gust}, " +
                  $"направление {weather.Wind.Deg}° ({Format.Compass(weather.Wind.Deg)})");

Console.WriteLine($"  Влажность:    {weather.Main.Humidity} %");
Console.WriteLine($"  Облачность:   {weather.Clouds?.All} %");

// visibility может не прийти — печатаем только когда есть.
if (weather.Visibility is { } visibility)
{
    Console.WriteLine($"  Видимость:    {visibility} м");
}

Console.WriteLine($"  Давление:     {weather.Main.Pressure} гПа");

var assessment = ConditionsAssessment.Assess(weather);
Console.WriteLine($"\nВЕРДИКТ: {assessment.Verdict}");

foreach (var reason in assessment.Critical)
{
    Console.WriteLine($"  [!] {reason}");
}
foreach (var reason in assessment.Warnings)
{
    Console.WriteLine($"  [~] {reason}");
}
if (assessment.Verdict == ConditionsAssessment.Allow)
{
    Console.WriteLine("  Ограничений по погоде нет.");
}

if (apiKey == SandboxKey)
{
    Console.WriteLine("\nПесочница: значения генерируются и могут противоречить друг другу и");
    Console.WriteLine("координатам (name не связан с coord, дождь при -14 °C — норма для мока).");
    Console.WriteLine("Проверять логику приложения на них можно, делать выводы о погоде — нет.");
}

return 0;

// ── Клиент ───────────────────────────────────────────────────────────────────

/// <summary>Ошибка API: HTTP-код разложен в человекочитаемую причину.</summary>
public sealed class AtloriumException(HttpStatusCode status, string body)
    : Exception($"HTTP {(int)status}: {Explain(status)}. Ответ сервера: {body[..Math.Min(200, body.Length)]}")
{
    public HttpStatusCode Status { get; } = status;

    private static string Explain(HttpStatusCode status) => (int)status switch
    {
        400 => "Координаты вне диапазона (широта -90..90, долгота -180..180)",
        401 => "API-ключ отсутствует, просрочен или недействителен",
        402 => "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        429 => "Превышен лимит запросов — повторите позже",
        500 => "Внутренняя ошибка при получении погодных данных",
        503 => "Погодный сервис временно недоступен (за сбой на своей стороне мы не списываем деньги)",
        _ => "Неизвестная ошибка",
    };
}

public sealed class WeatherClient(HttpClient http)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    // Одиночный запуск укладывается в лимит
    // с запасом, но если запускать пример в цикле, 429 придёт быстро.
    private static readonly TimeSpan RetryDelay = TimeSpan.FromSeconds(20);
    private const int MaxRetries = 1;

    // Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно отвечает Retry-After на
    // десятки минут, и клиент, слепо доверяющий заголовку, «зависнет» на всё это время
    // (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
    private static readonly TimeSpan MaxRetryDelay = TimeSpan.FromSeconds(120);

    /// <summary>
    /// Текущая погода в точке: GET /api/Weather?latitude=..&amp;longitude=..
    /// Ответ: температура в °C, скорость ветра в м/с,
    /// dt — Unix timestamp (UTC), timezone — смещение точки в СЕКУНДАХ.
    /// </summary>
    public async Task<Weather> GetWeatherAsync(double latitude, double longitude)
    {
        // InvariantCulture обязателен: под ru-RU разделитель дробной части — запятая,
        // и координаты уехали бы в запрос как «55,7558».
        var path = $"/api/Weather?latitude={latitude.ToString(CultureInfo.InvariantCulture)}" +
                   $"&longitude={longitude.ToString(CultureInfo.InvariantCulture)}";

        for (var attempt = 0; ; attempt++)
        {
            using var response = await http.GetAsync(path);

            // 429 — не поломка, а реальный лимит продукта.
            if (response.StatusCode == HttpStatusCode.TooManyRequests && attempt < MaxRetries)
            {
                var delay = RetryAfter(response);
                if (delay == TimeSpan.Zero)
                {
                    throw new AtloriumException(
                        HttpStatusCode.TooManyRequests, "лимит по IP исчерпан надолго, повторите позже");
                }
                await Console.Error.WriteLineAsync($"  ... лимит запросов, пауза {delay.TotalSeconds:F0} с");
                await Task.Delay(delay);
                continue;
            }

            var body = await response.Content.ReadAsStringAsync();
            if (!response.IsSuccessStatusCode)
            {
                throw new AtloriumException(response.StatusCode, body);
            }

            return JsonSerializer.Deserialize<Weather>(body, JsonOptions)
                   ?? throw new InvalidOperationException("Пустой ответ API.");
        }
    }

    /// <summary>
    /// Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
    /// 0 означал бы busy-loop, а десятки минут (так сервер отвечает на исчерпанный
    /// часовой лимит) — зависание клиента. TimeSpan.Zero = «ждать бессмысленно».
    /// </summary>
    private static TimeSpan RetryAfter(HttpResponseMessage response)
    {
        var delta = response.Headers.RetryAfter?.Delta;
        if (delta is not { } wait || wait <= TimeSpan.Zero)
        {
            return RetryDelay;
        }
        return wait <= MaxRetryDelay ? wait : TimeSpan.Zero;
    }
}

// ── Модель ответа (snake_case, °C, м/с) ─────────────────
// JsonSerializerDefaults.Web сам сопоставляет camelCase, но snake_case-поля
// (feels_like, temp_min, grnd_level) приходится размечать явно.

public sealed record Coord
{
    public double Lat { get; init; }
    public double Lon { get; init; }
}

/// <summary>Состояние неба. Main — машинный код: Clear | Clouds | Rain | Snow | Fog | Drizzle.</summary>
public sealed record Condition
{
    public int Id { get; init; }
    public string Main { get; init; } = "";
    public string Description { get; init; } = "";
    public string Icon { get; init; } = "";
}

public sealed record MainBlock
{
    public double Temp { get; init; }

    [JsonPropertyName("feels_like")]
    public double FeelsLike { get; init; }

    public int Pressure { get; init; }
    public int Humidity { get; init; }

    [JsonPropertyName("temp_min")]
    public double TempMin { get; init; }

    [JsonPropertyName("temp_max")]
    public double TempMax { get; init; }

    [JsonPropertyName("sea_level")]
    public int? SeaLevel { get; init; }

    [JsonPropertyName("grnd_level")]
    public int? GrndLevel { get; init; }
}

/// <summary>Ветер. Gust (порывы) отсутствует при штиле — отсюда nullable.</summary>
public sealed record Wind
{
    public double Speed { get; init; }
    public int Deg { get; init; }
    public double? Gust { get; init; }
}

public sealed record Clouds
{
    /// <summary>Облачность в процентах.</summary>
    public int All { get; init; }
}

/// <summary>Осадки за 1 и 3 часа, мм. Приходит null, если осадков нет.</summary>
public sealed record Precipitation
{
    [JsonPropertyName("1h")]
    public double? OneHour { get; init; }

    [JsonPropertyName("3h")]
    public double? ThreeHours { get; init; }
}

public sealed record Sys
{
    public int? Type { get; init; }
    public int? Id { get; init; }
    public double? Message { get; init; }
    public string? Country { get; init; }
    public long Sunrise { get; init; }
    public long Sunset { get; init; }
}

/// <summary>Полный ответ сервиса погоды.</summary>
public sealed record Weather
{
    public Coord? Coord { get; init; }

    /// <summary>Массив weather[] — состояния неба. Имя свойства изменено, чтобы не совпадать с типом.</summary>
    [JsonPropertyName("weather")]
    public IReadOnlyList<Condition> Conditions { get; init; } = [];

    public string Base { get; init; } = "";
    public MainBlock Main { get; init; } = new();

    /// <summary>Видимость в метрах, максимум 10000.</summary>
    public int? Visibility { get; init; }

    public Wind Wind { get; init; } = new();
    public Clouds? Clouds { get; init; }
    public Precipitation? Rain { get; init; }
    public Precipitation? Snow { get; init; }

    /// <summary>Время замера, Unix timestamp в UTC.</summary>
    public long Dt { get; init; }

    public Sys? Sys { get; init; }

    /// <summary>Смещение точки от UTC в СЕКУНДАХ: 10800 = UTC+3.</summary>
    public int Timezone { get; init; }

    public int Id { get; init; }

    /// <summary>Название населённого пункта.</summary>
    public string Name { get; init; } = "";

    public int Cod { get; init; }
}

// ── Применение данных: допуск бригады к работам ───────────────────────────────
// Погода сама по себе — просто JSON. Ценность появляется, когда по ней принимают
// решение: выпускать машину на маршрут, поднимать людей на высоту, начинать
// наружные работы — или отменять. Ниже — ровно это решение.

/// <summary>Вердикт и причины, каждая со ссылкой на поле ответа.</summary>
public sealed record Assessment(
    string Verdict,
    IReadOnlyList<string> Critical,
    IReadOnlyList<string> Warnings);

public static class ConditionsAssessment
{
    public const string Allow = "МОЖНО РАБОТАТЬ";
    public const string Review = "РАБОТАТЬ С ОСТОРОЖНОСТЬЮ";
    public const string Block = "РАБОТЫ ОТМЕНИТЬ";

    // Пороги принятия решения. Вынесены в константы намеренно: у логистики,
    // кровельщиков и монтажников высотных конструкций пороги разные.
    private const double FreezingTemp = 0;        // °C, main.temp: ниже — заморозки
    private const double IceTempMin = -5;         // °C, нижняя граница «гололёдного окна»
    private const double IceTempMax = 3;          // °C, верхняя граница «гололёдного окна»
    private const double StrongWind = 10;         // м/с, wind.speed: охрана труда на высоте
    private const double StrongGust = 15;         // м/с, wind.gust: порывы
    private const int LowVisibility = 1000;       // м, visibility: туман
    private const double ExtremeFeelsLike = -20;  // °C, main.feels_like: переохлаждение

    /// <summary>Осадки, дающие жидкую воду на дорожном покрытии (weather[].main).</summary>
    private static readonly HashSet<string> LiquidPrecipitation = ["Rain", "Drizzle"];

    /// <summary>
    /// Есть ли жидкие осадки: по weather[].main или по непустому полю rain.
    /// Поле rain приходит как null, если дождя нет, — обращаться к Rain.OneHour без
    /// проверки нельзя, получите NullReferenceException.
    /// </summary>
    private static bool HasPrecipitation(Weather weather)
        => weather.Conditions.Any(condition => LiquidPrecipitation.Contains(condition.Main))
           || weather.Rain is not null;

    /// <summary>Оценка погодных условий для выезда бригады / наружных и высотных работ.</summary>
    public static Assessment Assess(Weather weather)
    {
        var critical = new List<string>();
        var warnings = new List<string>();

        var temp = weather.Main.Temp;
        var feelsLike = weather.Main.FeelsLike;

        // 1. ГОЛОЛЁД — самое ценное, что видно из ответа. Не «холодно» и не «дождь» по
        //    отдельности, а именно их комбинация: вода на покрытии при температуре около
        //    нуля замерзает. Именно эта связка губит логистику.
        if (temp is >= IceTempMin and <= IceTempMax && HasPrecipitation(weather))
        {
            critical.Add($"Высокая вероятность гололёда: {temp:F1} °C и осадки — дорожное покрытие " +
                         $"опасно (main.temp в диапазоне {IceTempMin:F0}..{IceTempMax:F0} °C " +
                         $"+ weather[].main / rain)");
        }

        // 2. Сильный ветер. По правилам охраны труда работы на высоте ограничивают
        //    начиная с 10 м/с, полностью прекращают при 15 м/с и выше.
        if (weather.Wind.Speed >= StrongWind)
        {
            critical.Add($"Сильный ветер {weather.Wind.Speed:F1} м/с — опасно для высотных " +
                         $"и наружных работ (wind.speed >= {StrongWind:F0})");
        }

        // 3. Плохая видимость: туман. Для перевозок это прямой стоп-фактор.
        if (weather.Visibility is { } visibility && visibility < LowVisibility)
        {
            critical.Add($"Видимость {visibility} м — туман, опасно для перевозок " +
                         $"(visibility < {LowVisibility})");
        }

        // 4. Заморозки без осадков: не отмена, но риск (обледенение оборудования,
        //    застывание растворов, техника не заводится).
        if (temp <= FreezingTemp)
        {
            warnings.Add($"Заморозки: {temp:F1} °C (main.temp <= {FreezingTemp:F0})");
        }

        // 5. Порывы ветра. Средняя скорость может быть приемлемой, а порыв — сорвать
        //    лист профнастила с крыши. Поэтому gust проверяется отдельно от speed.
        if (weather.Wind.Gust is { } gust && gust >= StrongGust)
        {
            warnings.Add($"Порывы ветра до {gust:F1} м/с (wind.gust >= {StrongGust:F0})");
        }

        // 6. Экстремальная «ощущаемая» температура: время непрерывной работы на улице
        //    надо ограничивать, а перерывы на обогрев — вводить.
        if (feelsLike <= ExtremeFeelsLike)
        {
            warnings.Add($"Ощущается как {feelsLike:F1} °C — ограничить время работы на улице " +
                         $"(main.feels_like <= {ExtremeFeelsLike:F0})");
        }

        var verdict = critical.Count > 0 ? Block : warnings.Count > 0 ? Review : Allow;
        return new Assessment(verdict, critical, warnings);
    }
}

// ── Форматирование ────────────────────────────────────────────────────────────

public static class Format
{
    private static readonly string[] CompassPoints = ["С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"];

    /// <summary>Румб по направлению ветра в градусах (wind.deg).</summary>
    public static string Compass(int deg) => CompassPoints[(int)Math.Round(deg / 45.0) % 8];

    /// <summary>
    /// dt — Unix timestamp в UTC, timezone — смещение точки в СЕКУНДАХ (10800 = UTC+3).
    /// Смещение надо применить руками: сервер не присылает готовое локальное время.
    /// </summary>
    public static string LocalTime(long dt, int offsetSeconds)
    {
        var offset = TimeSpan.FromSeconds(offsetSeconds);
        var moment = DateTimeOffset.FromUnixTimeSeconds(dt).ToOffset(offset);
        return $"{moment:dd.MM.yyyy HH:mm} (местное время, UTC{offsetSeconds / 3600:+#;-#;+0})";
    }
}
