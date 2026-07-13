# Weather API by coordinates — temperature, wind and precipitation by latitude and longitude

[Русский](README.md) · **English**

[![examples](https://github.com/atlorium-api/weather-api-client/actions/workflows/examples.yml/badge.svg)](https://github.com/atlorium-api/weather-api-client/actions/workflows/examples.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-Swagger-brightgreen)](https://atlorium.com/weatherAPI)

Ready-to-run examples for the **current weather API** in six languages: **Python, TypeScript (Node.js), Go, Java, C#, PHP.**
Get **temperature by latitude and longitude**, **wind speed** and gusts, humidity, cloud cover, visibility and precipitation in a single HTTP request. The response is a plain **weather JSON API** payload, ready to drop into your app.

Every example **runs out of the box — no signup, no key, no card.** A public demo key is baked in.

```bash
git clone https://github.com/atlorium-api/weather-api-client
cd weather-api-client/python && pip install -r requirements.txt && python main.py
```

```
Погода по координатам 55.7558, 37.6173
  Точка:        Махачкала (RU), coord из ответа: 55.7558, 37.6173
  Замер:        13.07.2026 18:02 (местное время, UTC+3)
  Небо:         небольшой дождь (Rain)
  Температура:  -14.0 °C, ощущается как -16.2 °C
  Ветер:        3.3 м/с, порывы до 16.6 м/с, направление 62° (СВ)
  Влажность:    80 %
  Облачность:   77 %
  Видимость:    5475 м
  Давление:     995 гПа

ВЕРДИКТ: РАБОТАТЬ С ОСТОРОЖНОСТЬЮ
  [~] Заморозки: -14.0 °C (main.temp <= 0)
  [~] Порывы ветра до 16.6 м/с (wind.gust >= 15)
```

(The examples print in Russian; the verdict above reads "PROCEED WITH CAUTION — frost, wind gusts".)

> This is the **real, unedited output** of the example on the demo key. Yes, it contradicts itself — see [What is wrong with sandbox data](#what-is-wrong-with-sandbox-data). That is by design: you can write and test the integration before paying, and with a live key the same code returns real measurements.

---

## What it is for

Weather data is rarely the goal in itself — you fetch it to **make a call**: dispatch the truck or not, send the crew up the scaffolding or not, start the outdoor job or postpone it, grit the road or skip it. Plus the ordinary product cases: a weather widget, a "take an umbrella" nudge, weather-driven sales analytics.

So the examples do not just print JSON — they **apply** it. Each ships an `assessConditions()` function that turns the response into a three-state verdict — **PROCEED / PROCEED WITH CAUTION / STAND DOWN** — and lists the reasons, each pointing at the field it was derived from.

| Check | Condition on response fields | Weight |
|---|---|---|
| **Black ice** | `main.temp` between −5 and +3 °C **AND** precipitation (`weather[].main` = `Rain`/`Drizzle`, or `rain` ≠ `null`) | blocker |
| Strong wind | `wind.speed >= 10` m/s | blocker |
| Poor visibility | `visibility < 1000` m — fog | blocker |
| Freezing | `main.temp <= 0` °C | warning |
| Wind gusts | `wind.gust >= 15` m/s | warning |
| Extreme cold | `main.feels_like <= -20` °C | warning |

The valuable one is **black ice**, and no single field shows it. Neither "−1 °C" nor "it is raining" means anything on its own. Their **combination** — liquid water on the road at near-freezing temperature — is exactly what wrecks logistics: trucks in ditches, missed slots, damaged cargo. One `if` over two response fields, and you know an hour before dispatch.

Thresholds live in constants — a haulier, a roofer and a crane operator do not share them. Tune to your case.

## Quick start

Try the API without cloning anything:

```bash
curl -H "Authorization: Bearer ak_sandbox_demo_mockdata_v1" \
     "https://atlorium.com/api/Weather?latitude=55.7558&longitude=37.6173"
```

| Language | Run | Requires |
|----------|-----|----------|
| [Python](python/) | `pip install -r requirements.txt && python main.py` | Python 3.10+ |
| [TypeScript / Node.js](node/) | `npm install && npm start` | Node.js 20+ |
| [Go](go/) | `go run .` | Go 1.22+ |
| [Java](java/) | `java Main.java` | JDK 11+ (no dependencies) |
| [C#](csharp/) | `dotnet run` | .NET 8+ |
| [PHP](php/) | `php main.php` | PHP 8.1+ |

Pass your own coordinates as two arguments (Moscow is the default):

```bash
python main.py 59.9343 30.3351      # Saint Petersburg
go run . 51.5074 -0.1278            # London
npm start -- -33.8688 151.2093      # Sydney
```

## Authentication

The key goes in the `Authorization` header:

```
Authorization: Bearer YOUR_KEY
```

| Key | Behaviour |
|-----|-----------|
| `ak_sandbox_demo_mockdata_v1` | **Demo key.** Public, shared by everyone. Returns mocks, charges nothing, needs no account. Responses are keyed to the rounded coordinates, so one point always yields the same "weather" — you can assert on it in tests. |
| Live key | Real weather data. Get one at [atlorium.com](https://atlorium.com) |

Switching to a live key requires **no code changes** — every example reads an environment variable:

```bash
export ATLORIUM_API_KEY="ak_your_live_key"
```

Every sandbox response carries the header `X-Atlorium-Sandbox: true`, so mock data can never be mistaken for real data.

## What is wrong with sandbox data

Stated plainly, because you will notice it within a minute anyway.

The demo key returns **generated** values. Each is plausible on its own and stable per point, but they are **not consistent with each other or with the coordinates**. The output above shows it twice:

- **`name` is unrelated to the coordinates.** Moscow's coordinates (55.7558, 37.6173) came back with `name` = "Makhachkala". `coord`, meanwhile, honestly echoes what you sent.
- **The weather is physically absurd.** "Light rain" (`Rain`) at `temp = −13.99 °C` does not happen in nature.

The practical takeaway: **the sandbox is fine — and useful — for exercising your own logic**: fields, types, nulls, code branches, verdicts. **It is not fine for drawing conclusions about the weather.** With a live key you get real station measurements and the contradictions disappear.

Separately: `rain` and `snow` are **always `null`** in the sandbox. In production they arrive as `{"1h": 0.5}` when there is precipitation. The examples account for this — touching `rain["1h"]` without a null check gives you a `TypeError` / `NullReferenceException` / panic, which is exactly why the check lives in its own `hasPrecipitation()` function.

## Endpoints

Base URL: `https://atlorium.com`

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/Weather` | Current weather at a point, by geographic coordinates |

This is the **only** endpoint. It does not return a multi-day forecast — only the current state at request time.

### `GET /api/Weather`

| Parameter | In | Type | Description |
|-----------|----|------|-------------|
| `latitude` | query | number | **Latitude** in degrees, −90.0 to +90.0. Positive = Northern hemisphere (55.7558 — Moscow), negative = Southern (−33.8688 — Sydney) |
| `longitude` | query | number | **Longitude** in degrees, −180.0 to +180.0. Positive = Eastern hemisphere (37.6173 — Moscow), negative = Western (−74.0060 — New York) |

The path is case-insensitive: `/api/weather` and `/api/Weather` are the same.

## Response fields

Field names are `snake_case`, units are metric.

| Field | Type | Meaning |
|-------|------|---------|
| `coord` | object | `{ lat, lon }` — an **echo of your request**, not a resolved nearest station |
| `weather` | array | Sky conditions, usually one element: `{ id, main, description, icon }` |
| `weather[].main` | string | Machine code: `Clear`, `Clouds`, `Rain`, `Drizzle`, `Snow`, `Fog`. **Branch on this**, not on `description` |
| `weather[].description` | string | Human-readable text, localised: "light rain" |
| `weather[].icon` | string | Weather icon code: `10d`, `01n` |
| `base` | string | Data source, usually `stations` |
| `main.temp` | number | **Temperature in degrees Celsius** (not Kelvin) |
| `main.feels_like` | number | Apparent temperature, °C — wind chill and humidity applied |
| `main.temp_min` / `main.temp_max` | number | Min and max across the settlement's area, °C |
| `main.pressure` | number | Atmospheric pressure, hPa |
| `main.humidity` | number | Humidity, % |
| `main.sea_level` / `main.grnd_level` | number\|null | Pressure at sea level and ground level, hPa |
| `visibility` | number\|null | **Visibility in metres**, capped at 10000 |
| `wind.speed` | number | **Wind speed in m/s** (not km/h) |
| `wind.deg` | number | Wind direction in degrees (0 = north, 90 = east) |
| `wind.gust` | number\|null | Wind gusts, m/s. **May be absent** — calm air has no gusts |
| `clouds.all` | number | Cloud cover, % |
| `rain` / `snow` | object\|null | Precipitation, mm: `{ "1h": …, "3h": … }`. **`null` when there is none** |
| `dt` | number | Measurement time — **Unix timestamp in UTC** |
| `timezone` | number | The point's UTC offset **in SECONDS**: `10800` = UTC+3 |
| `sys.country` | string | ISO 3166 country code |
| `sys.sunrise` / `sys.sunset` | number | Sunrise and sunset, Unix timestamps in UTC |
| `id` | number | Internal settlement id |
| `name` | string | Settlement name |
| `cod` | number | In-body status code, `200` on success |

Three things people trip over:

1. **`dt` is UTC and `timezone` is an offset in SECONDS, not hours.** The server never sends ready-made local time: you add `dt + timezone` yourself. All six examples do it in `localTime()`.
2. **`wind.gust`, `visibility`, `rain`, `snow` are nullable.** Dereferencing them unchecked crashes your app.
3. **Temperature is °C, wind is m/s.** For km/h, multiply by 3.6.

## Error handling

| Code | Cause | What to do |
|------|-------|------------|
| `400` | Coordinates out of range | Latitude −90…90, longitude −180…180 |
| `401` | Key missing, expired or invalid | Check the `Authorization` header |
| `402` | Insufficient credit balance | Top up at [atlorium.com](https://atlorium.com) |
| `429` | Rate limit exceeded | Retry with backoff — see below |
| `500` | Internal error while fetching weather | Retry later |
| `503` | Upstream weather provider unavailable | Retry later. **You are not charged for our failures** |

All six examples map these codes to human-readable causes — see the `AtloriumError` class.

**About 429 and `Retry-After`.** The server honestly tells you how long to wait. But once the **hourly** quota is gone, it may ask for tens of minutes — and a client that blindly trusts the header hangs for all of it (and burns your CI job budget). So the examples carry a **wait ceiling**, `MAX_RETRY_DELAY = 120` seconds: beyond that we stop waiting, report "quota exhausted" and exit. Copy the pattern.

## Pricing and limits

**Pay-as-you-go, no subscription** — you pay per successful request.

Weather limits are generous but finite: enough to build an integration and run a widget, not enough to turn the service into a personal weather proxy. The demo key is rate-limited **per IP** with the very same numbers a paying client gets — the sandbox shows you the terms you will actually get. Current limits and prices: [atlorium.com/pricing](https://atlorium.com/pricing). Do not loop over the examples.

Current prices and limits: **[atlorium.com/pricing](https://atlorium.com/pricing)**

## FAQ

**Where does the data come from?** Real station measurements at request time, passed through without repackaging.

**Can I get a 7-day forecast?** No. There is one endpoint and it returns the **current** weather at a point. If you specifically need a multi-day forecast, this service is not it — better to know now than after integrating.

**What units is the temperature in?** Degrees Celsius — the conversion is already done on our side. Print `temp` as-is.

**How do I convert `dt` to the point's local time?** Add `dt` (Unix, UTC) to the `timezone` offset (in seconds). It is the offset of the **point**, not of your server. All six examples ship a `localTime()` helper.

**What if `wind.gust` is missing?** Treat it as "no gusts". The field is absent in light wind — that is not an error. Same for `rain` and `snow`.

**Can I look up weather by city name?** No, coordinates only. The city name comes back **in the response** (`name`); it is not an input. To turn an address into coordinates use [Address standardization](https://github.com/atlorium-api/address-standardization-api-client) or [GAR / FIAS addresses](https://github.com/atlorium-api/gar-fias-address-api-client); to turn an IP into coordinates use [IP geolocation](https://github.com/atlorium-api/ip-geolocation-api-client).

**Do I need to register to try it?** No. The demo key is public and works without an account — but returns mocks, not real data.

## Other Atlorium APIs

The same account and the same key also give you:

- [GAR/FIAS addresses](https://github.com/atlorium-api/gar-fias-address-api-client) — search and suggestions from the official registry
- [Cron expression parser](https://github.com/atlorium-api/cron-expression-parser-api-client) — schedule validation and next run times with timezones
- [Address standardization](https://github.com/atlorium-api/address-standardization-api-client) — parse a string into components, quality score
- [SSL certificate check](https://github.com/atlorium-api/ssl-certificate-check-api-client) — expiry, SAN, chain of trust
- [Phone validation](https://github.com/atlorium-api/phone-validation-api-client) — format, line type, range operator
- [DNS Lookup](https://github.com/atlorium-api/dns-lookup-api-client) — domain records, MX, SPF, DMARC

Full catalogue: [atlorium.com](https://atlorium.com)

## Links

- **API reference (Swagger):** [atlorium.com/weatherAPI](https://atlorium.com/weatherAPI)
- **OpenAPI spec:** [weather_en-US.json](https://atlorium.com/openapi/weather_en-US.json)
- **Support:** support@atlorium.com

## License

[MIT](LICENSE)
