<?php

/**
 * Клиент API погоды Atlorium — текущая погода по географическим координатам.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   php main.php
 *   php main.php 59.9343 30.3351
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

declare(strict_types=1);

/**
 * Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальными
 * данными) — чтобы можно было встроить и протестировать интеграцию до оплаты.
 * Ответы привязаны к округлённым координатам: одна и та же точка всегда даёт одну
 * и ту же «погоду», поэтому на них можно писать стабильные тесты.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const TIMEOUT = 30;

// Одиночный запуск укладывается в лимит
// с запасом, но если запускать пример в цикле, 429 придёт быстро.
const RETRY_DELAY = 20;
const MAX_RETRIES = 1;

// Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно отвечает Retry-After на
// десятки минут — и клиент, слепо доверяющий заголовку, «зависнет» на всё это время
// (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
const MAX_RETRY_DELAY = 120;

// ── Пороги принятия решения ───────────────────────────────────────────────────
// Вынесены в константы намеренно: у логистики, кровельщиков и монтажников высотных
// конструкций пороги разные.

const FREEZING_TEMP = 0.0;         // °C, main.temp: ниже — заморозки
const ICE_TEMP_MIN = -5.0;         // °C, нижняя граница «гололёдного окна»
const ICE_TEMP_MAX = 3.0;          // °C, верхняя граница «гололёдного окна»
const STRONG_WIND = 10.0;          // м/с, wind.speed: охрана труда на высоте
const STRONG_GUST = 15.0;          // м/с, wind.gust: порывы
const LOW_VISIBILITY = 1000;       // м, visibility: туман
const EXTREME_FEELS_LIKE = -20.0;  // °C, main.feels_like: переохлаждение

/** Осадки, дающие жидкую воду на дорожном покрытии (weather[].main). */
const LIQUID_PRECIPITATION = ['Rain', 'Drizzle'];

const ALLOW = 'МОЖНО РАБОТАТЬ';
const REVIEW = 'РАБОТАТЬ С ОСТОРОЖНОСТЬЮ';
const BLOCK = 'РАБОТЫ ОТМЕНИТЬ';

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
final class AtloriumError extends RuntimeException
{
    private const REASONS = [
        400 => 'Координаты вне диапазона (широта -90..90, долгота -180..180)',
        401 => 'API-ключ отсутствует, просрочен или недействителен',
        402 => 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
        429 => 'Превышен лимит запросов — повторите позже',
        500 => 'Внутренняя ошибка при получении погодных данных',
        503 => 'Погодный сервис временно недоступен (за сбой на своей стороне мы не списываем деньги)',
    ];

    public function __construct(public readonly int $status, string $body)
    {
        $reason = self::REASONS[$status] ?? 'Неизвестная ошибка';
        parent::__construct(sprintf(
            'HTTP %d: %s. Ответ сервера: %s',
            $status,
            $reason,
            mb_substr($body, 0, 200)
        ));
    }
}

final class WeatherClient
{
    private string $apiKey;
    private string $baseUrl;

    public function __construct(?string $apiKey = null, ?string $baseUrl = null)
    {
        $this->apiKey = $apiKey ?? (getenv('ATLORIUM_API_KEY') ?: SANDBOX_KEY);
        $this->baseUrl = $baseUrl ?? (getenv('ATLORIUM_BASE_URL') ?: 'https://atlorium.com');
    }

    public function isSandbox(): bool
    {
        return $this->apiKey === SANDBOX_KEY;
    }

    /**
     * Текущая погода в точке: GET /api/Weather?latitude=..&longitude=..
     *
     * Ответ: температура в °C, скорость ветра в м/с,
     * dt — Unix timestamp (UTC), timezone — смещение точки в СЕКУНДАХ.
     *
     * @return array<string, mixed>
     */
    public function getWeather(float $latitude, float $longitude): array
    {
        $url = $this->baseUrl . '/api/Weather?' . http_build_query([
            'latitude' => $latitude,
            'longitude' => $longitude,
        ]);

        for ($attempt = 0; ; $attempt++) {
            $curl = curl_init($url);
            curl_setopt_array($curl, [
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_TIMEOUT => TIMEOUT,
                CURLOPT_HEADER => true,
                CURLOPT_HTTPHEADER => [
                    'Authorization: Bearer ' . $this->apiKey,
                    'Accept: application/json',
                ],
            ]);

            $raw = curl_exec($curl);
            if ($raw === false) {
                $error = curl_error($curl);
                curl_close($curl);
                throw new RuntimeException("Сетевая ошибка: {$error}");
            }

            $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
            $headerSize = curl_getinfo($curl, CURLINFO_HEADER_SIZE);
            curl_close($curl);

            $headers = substr((string) $raw, 0, $headerSize);
            $body = substr((string) $raw, $headerSize);

            // 429 — не поломка, а реальный лимит продукта.
            if ($status === 429 && $attempt < MAX_RETRIES) {
                $delay = $this->retryAfter($headers);
                if ($delay === 0) {
                    throw new AtloriumError(429, 'лимит по IP исчерпан надолго, повторите позже');
                }
                fwrite(STDERR, "  ... лимит запросов, пауза {$delay} с\n");
                sleep($delay);
                continue;
            }

            if ($status !== 200) {
                throw new AtloriumError($status, $body);
            }

            return json_decode($body, true, 512, JSON_THROW_ON_ERROR);
        }
    }

    /**
     * Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
     * 0 означал бы busy-loop, а десятки минут (так сервер отвечает на исчерпанный
     * часовой лимит) — зависание клиента. Ноль означает «ждать бессмысленно».
     */
    private function retryAfter(string $headers): int
    {
        if (preg_match('/^Retry-After:\s*(\d+)/mi', $headers, $match) !== 1) {
            return RETRY_DELAY;
        }
        $seconds = (int) $match[1];
        if ($seconds <= 0) {
            return RETRY_DELAY;
        }

        return $seconds <= MAX_RETRY_DELAY ? $seconds : 0;
    }
}

// ── Применение данных: допуск бригады к работам ───────────────────────────────
// Погода сама по себе — просто JSON. Ценность появляется, когда по ней принимают
// решение: выпускать машину на маршрут, поднимать людей на высоту, начинать
// наружные работы — или отменять. Ниже — ровно это решение.

/**
 * Есть ли жидкие осадки: по weather[].main или по непустому полю rain.
 * Поле rain приходит как null, если дождя нет, — обращаться к $w['rain']['1h']
 * без проверки нельзя.
 *
 * @param array<string, mixed> $weather
 */
function hasPrecipitation(array $weather): bool
{
    foreach ($weather['weather'] ?? [] as $condition) {
        if (in_array($condition['main'] ?? '', LIQUID_PRECIPITATION, true)) {
            return true;
        }
    }

    return ($weather['rain'] ?? null) !== null;
}

/**
 * Оценка погодных условий для выезда бригады / наружных и высотных работ.
 *
 * @param array<string, mixed> $weather
 * @return array{verdict: string, critical: list<string>, warnings: list<string>}
 */
function assessConditions(array $weather): array
{
    $critical = [];
    $warnings = [];

    $temp = $weather['main']['temp'] ?? null;
    $feelsLike = $weather['main']['feels_like'] ?? null;
    $speed = $weather['wind']['speed'] ?? null;
    $gust = $weather['wind']['gust'] ?? null;      // может быть null: при штиле порывов нет
    $visibility = $weather['visibility'] ?? null;

    // 1. ГОЛОЛЁД — самое ценное, что видно из ответа. Не «холодно» и не «дождь» по
    //    отдельности, а именно их комбинация: вода на покрытии при температуре около
    //    нуля замерзает. Именно эта связка губит логистику.
    if ($temp !== null && $temp >= ICE_TEMP_MIN && $temp <= ICE_TEMP_MAX && hasPrecipitation($weather)) {
        $critical[] = sprintf(
            'Высокая вероятность гололёда: %.1f °C и осадки — дорожное покрытие опасно '
                . '(main.temp в диапазоне %.0f..%.0f °C + weather[].main / rain)',
            $temp,
            ICE_TEMP_MIN,
            ICE_TEMP_MAX
        );
    }

    // 2. Сильный ветер. По правилам охраны труда работы на высоте ограничивают
    //    начиная с 10 м/с, полностью прекращают при 15 м/с и выше.
    if ($speed !== null && $speed >= STRONG_WIND) {
        $critical[] = sprintf(
            'Сильный ветер %.1f м/с — опасно для высотных и наружных работ (wind.speed >= %.0f)',
            $speed,
            STRONG_WIND
        );
    }

    // 3. Плохая видимость: туман. Для перевозок это прямой стоп-фактор.
    if ($visibility !== null && $visibility < LOW_VISIBILITY) {
        $critical[] = sprintf(
            'Видимость %d м — туман, опасно для перевозок (visibility < %d)',
            $visibility,
            LOW_VISIBILITY
        );
    }

    // 4. Заморозки без осадков: не отмена, но риск (обледенение оборудования,
    //    застывание растворов, техника не заводится).
    if ($temp !== null && $temp <= FREEZING_TEMP) {
        $warnings[] = sprintf('Заморозки: %.1f °C (main.temp <= %.0f)', $temp, FREEZING_TEMP);
    }

    // 5. Порывы ветра. Средняя скорость может быть приемлемой, а порыв — сорвать
    //    лист профнастила с крыши. Поэтому gust проверяется отдельно от speed.
    if ($gust !== null && $gust >= STRONG_GUST) {
        $warnings[] = sprintf('Порывы ветра до %.1f м/с (wind.gust >= %.0f)', $gust, STRONG_GUST);
    }

    // 6. Экстремальная «ощущаемая» температура: время непрерывной работы на улице
    //    надо ограничивать, а перерывы на обогрев — вводить.
    if ($feelsLike !== null && $feelsLike <= EXTREME_FEELS_LIKE) {
        $warnings[] = sprintf(
            'Ощущается как %.1f °C — ограничить время работы на улице (main.feels_like <= %.0f)',
            $feelsLike,
            EXTREME_FEELS_LIKE
        );
    }

    $verdict = $critical !== [] ? BLOCK : ($warnings !== [] ? REVIEW : ALLOW);

    return ['verdict' => $verdict, 'critical' => $critical, 'warnings' => $warnings];
}

// ── Форматирование ────────────────────────────────────────────────────────────

/** Румб по направлению ветра в градусах (wind.deg). */
function compass(int $deg): string
{
    $points = ['С', 'СВ', 'В', 'ЮВ', 'Ю', 'ЮЗ', 'З', 'СЗ'];

    return $points[(int) round($deg / 45) % 8];
}

/**
 * dt — Unix timestamp в UTC, timezone — смещение точки в СЕКУНДАХ (10800 = UTC+3).
 * Смещение надо применить руками: сервер не присылает готовое локальное время.
 *
 * Имя не localTime(): имена функций в PHP регистронезависимы, и такое объявление
 * столкнулось бы со встроенной localtime() — fatal error ещё на разборе файла.
 */
function formatLocalTime(int $dt, int $offsetSeconds): string
{
    $moment = (new DateTimeImmutable('@' . $dt))
        ->setTimezone(new DateTimeZone(sprintf(
            '%s%02d:%02d',
            $offsetSeconds < 0 ? '-' : '+',
            intdiv(abs($offsetSeconds), 3600),
            intdiv(abs($offsetSeconds) % 3600, 60)
        )));

    return sprintf(
        '%s (местное время, UTC%+d)',
        $moment->format('d.m.Y H:i'),
        intdiv($offsetSeconds, 3600)
    );
}

// ── Демонстрация ─────────────────────────────────────────────────────────────

$client = new WeatherClient();

if ($client->isSandbox()) {
    echo "Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n\n";
}

// Дефолт — Москва. Координаты передаются двумя аргументами: php main.php 59.93 30.33
$latitude = isset($argv[1]) ? (float) $argv[1] : 55.7558;
$longitude = isset($argv[2]) ? (float) $argv[2] : 37.6173;

try {
    $weather = $client->getWeather($latitude, $longitude);
} catch (AtloriumError $error) {
    fwrite(STDERR, "Ошибка: {$error->getMessage()}\n");
    exit(1);
}

$sky = $weather['weather'][0] ?? [];
$gust = $weather['wind']['gust'] ?? null;
$gustText = $gust !== null ? sprintf(', порывы до %.1f м/с', $gust) : '';

echo "Погода по координатам {$latitude}, {$longitude}\n";
printf(
    "  Точка:        %s (%s), coord из ответа: %s, %s\n",
    $weather['name'],
    $weather['sys']['country'] ?? '?',
    $weather['coord']['lat'],
    $weather['coord']['lon']
);
printf("  Замер:        %s\n", formatLocalTime((int) $weather['dt'], (int) $weather['timezone']));
printf("  Небо:         %s (%s)\n", $sky['description'] ?? '?', $sky['main'] ?? '?');
printf(
    "  Температура:  %.1f °C, ощущается как %.1f °C\n",
    $weather['main']['temp'],
    $weather['main']['feels_like']
);
printf(
    "  Ветер:        %.1f м/с%s, направление %d° (%s)\n",
    $weather['wind']['speed'],
    $gustText,
    $weather['wind']['deg'],
    compass((int) $weather['wind']['deg'])
);
printf("  Влажность:    %d %%\n", $weather['main']['humidity']);
printf("  Облачность:   %d %%\n", $weather['clouds']['all']);

// visibility может не прийти — печатаем только когда есть.
if (($weather['visibility'] ?? null) !== null) {
    printf("  Видимость:    %d м\n", $weather['visibility']);
}

printf("  Давление:     %d гПа\n", $weather['main']['pressure']);

$assessment = assessConditions($weather);
printf("\nВЕРДИКТ: %s\n", $assessment['verdict']);

foreach ($assessment['critical'] as $reason) {
    echo "  [!] {$reason}\n";
}
foreach ($assessment['warnings'] as $reason) {
    echo "  [~] {$reason}\n";
}
if ($assessment['verdict'] === ALLOW) {
    echo "  Ограничений по погоде нет.\n";
}

if ($client->isSandbox()) {
    echo "\nПесочница: значения генерируются и могут противоречить друг другу и\n";
    echo "координатам (name не связан с coord, дождь при -14 °C — норма для мока).\n";
    echo "Проверять логику приложения на них можно, делать выводы о погоде — нет.\n";
}
