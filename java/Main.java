/*
 * Клиент API погоды Atlorium — текущая погода по географическим координатам.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе).
 * Начиная с Java 11 файл запускается напрямую, без компиляции и без зависимостей:
 *
 *     java Main.java
 *     java Main.java 59.9343 30.3351
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    /**
     * Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальными
     * данными) — чтобы можно было встроить и протестировать интеграцию до оплаты.
     * Ответы привязаны к округлённым координатам: одна и та же точка всегда даёт одну
     * и ту же «погоду», поэтому на них можно писать стабильные тесты.
     */
    static final String SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1";

    static final String API_KEY = envOr("ATLORIUM_API_KEY", SANDBOX_KEY);
    static final String BASE_URL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com");

    // Одиночный запуск укладывается в лимит
    // с запасом, но если запускать пример в цикле, 429 придёт быстро.
    //
    // MAX_RETRY_DELAY — потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно
    // отвечает Retry-After на десятки минут, и клиент, слепо доверяющий заголовку,
    // «зависнет» на всё это время (а в CI просто съест бюджет джоба).
    static final int RETRY_DELAY_SECONDS = 20;
    static final int MAX_RETRIES = 1;
    static final int MAX_RETRY_DELAY_SECONDS = 120;

    // ── Пороги принятия решения ──────────────────────────────────────────────
    // Вынесены в константы намеренно: у логистики, кровельщиков и монтажников
    // высотных конструкций пороги разные.

    static final double FREEZING_TEMP = 0;         // °C, main.temp: ниже — заморозки
    static final double ICE_TEMP_MIN = -5;         // °C, нижняя граница «гололёдного окна»
    static final double ICE_TEMP_MAX = 3;          // °C, верхняя граница «гололёдного окна»
    static final double STRONG_WIND = 10;          // м/с, wind.speed: охрана труда на высоте
    static final double STRONG_GUST = 15;          // м/с, wind.gust: порывы
    static final int LOW_VISIBILITY = 1000;        // м, visibility: туман
    static final double EXTREME_FEELS_LIKE = -20;  // °C, main.feels_like: переохлаждение

    /** Осадки, дающие жидкую воду на дорожном покрытии (weather[].main). */
    static final Set<String> LIQUID_PRECIPITATION = Set.of("Rain", "Drizzle");

    /**
     * Числа форматируем в инвариантной локали. Без этого под ru-RU разделитель дробной
     * части — запятая, и температура печаталась бы как «-14,0», а под en-US — как
     * «-14.0»: один и тот же код давал бы разный вывод на разных машинах.
     */
    static final Locale NUM = Locale.ROOT;

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
    static class AtloriumException extends RuntimeException {
        private static final Map<Integer, String> REASONS = Map.of(
                400, "Координаты вне диапазона (широта -90..90, долгота -180..180)",
                401, "API-ключ отсутствует, просрочен или недействителен",
                402, "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
                429, "Превышен лимит запросов — повторите позже",
                500, "Внутренняя ошибка при получении погодных данных",
                503, "Погодный сервис временно недоступен (за сбой на своей стороне мы не списываем деньги)");

        final int status;

        AtloriumException(int status, String body) {
            super("HTTP " + status + ": "
                    + REASONS.getOrDefault(status, "Неизвестная ошибка")
                    + ". Ответ сервера: " + body.substring(0, Math.min(200, body.length())));
            this.status = status;
        }
    }

    /**
     * Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
     * 0 означал бы busy-loop, а десятки минут (так сервер отвечает на исчерпанный
     * часовой лимит) — зависание клиента. Ноль означает «ждать бессмысленно».
     */
    static int retryAfter(HttpResponse<byte[]> response) {
        int seconds = response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                })
                .orElse(0);

        if (seconds <= 0) {
            return RETRY_DELAY_SECONDS;
        }
        return seconds <= MAX_RETRY_DELAY_SECONDS ? seconds : 0;
    }

    /**
     * Текущая погода в точке: GET /api/Weather?latitude=..&longitude=..
     *
     * Ответ: температура в °C, скорость ветра в м/с,
     * dt — Unix timestamp (UTC), timezone — смещение точки в СЕКУНДАХ.
     */
    static String getWeather(double latitude, double longitude) throws IOException, InterruptedException {
        String url = BASE_URL + "/api/Weather?latitude=" + latitude + "&longitude=" + longitude;

        for (int attempt = 0; ; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String body = new String(response.body(), StandardCharsets.UTF_8);

            // 429 — не поломка, а реальный лимит продукта.
            if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
                int delay = retryAfter(response);
                if (delay == 0) {
                    throw new AtloriumException(429, "лимит по IP исчерпан надолго, повторите позже");
                }
                System.err.println("  ... лимит запросов, пауза " + delay + " с");
                Thread.sleep(delay * 1000L);
                continue;
            }

            if (response.statusCode() != 200) {
                throw new AtloriumException(response.statusCode(), body);
            }
            return body;
        }
    }

    // ── Разбор JSON ──────────────────────────────────────────────────────────
    // Пример намеренно оставлен без внешних зависимостей, чтобы запускаться одной
    // командой `java Main.java`. В рабочем проекте берите Jackson или Gson и маппьте
    // ответ в полноценную запись — эти регулярки существуют только ради отсутствия
    // pom.xml.
    //
    // Ответ погоды вложенный (main / wind / sys / clouds) и содержит массив weather[],
    // поэтому одними регулярками по всему тексту обойтись нельзя: поле "main" есть и
    // на верхнем уровне (объект с температурой), и внутри weather[] (строка "Rain").
    // Сначала вырезаем нужный кусок по балансу скобок, потом ищем поле уже в нём.

    /** Вырезает вложенный объект или массив по имени поля, считая баланс скобок. */
    static String block(String json, String field, char open, char close) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\\" + open).matcher(json);
        if (!matcher.find()) {
            return null;
        }

        int start = matcher.end() - 1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < json.length(); i++) {
            char symbol = json.charAt(i);

            if (escaped) {
                escaped = false;
            } else if (symbol == '\\') {
                escaped = true;
            } else if (symbol == '"') {
                inString = !inString;
            } else if (!inString) {
                if (symbol == open) {
                    depth++;
                } else if (symbol == close) {
                    depth--;
                    if (depth == 0) {
                        return json.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }

    /** Вложенный объект: obj(json, "main") → {"temp":-13.99,...} */
    static String obj(String json, String field) {
        return block(json, field, '{', '}');
    }

    /** Первый элемент массива объектов: first(json, "weather") → {"id":500,...} */
    static String first(String json, String field) {
        String array = block(json, field, '[', ']');
        if (array == null) {
            return null;
        }
        int start = array.indexOf('{');
        int end = array.lastIndexOf('}');
        return (start < 0 || end < start) ? null : array.substring(start, end + 1);
    }

    static String str(String json, String field) {
        if (json == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"") : null;
    }

    /** Число или null. Именно null, а не 0: поля gust, visibility, rain могут отсутствовать. */
    static Double number(String json, String field) {
        if (json == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return matcher.find() ? Double.valueOf(matcher.group(1)) : null;
    }

    // ── Применение данных: допуск бригады к работам ───────────────────────────
    // Погода сама по себе — просто JSON. Ценность появляется, когда по ней принимают
    // решение: выпускать машину на маршрут, поднимать людей на высоту, начинать
    // наружные работы — или отменять. Ниже — ровно это решение.

    static final String ALLOW = "МОЖНО РАБОТАТЬ";
    static final String REVIEW = "РАБОТАТЬ С ОСТОРОЖНОСТЬЮ";
    static final String BLOCK = "РАБОТЫ ОТМЕНИТЬ";

    /**
     * Вердикт и причины, каждая со ссылкой на поле ответа.
     * Обычный класс, а не record: record появился в Java 16, а этот пример должен
     * запускаться на JDK 11.
     */
    static final class Assessment {
        final String verdict;
        final List<String> critical;  // стоп-факторы → работы отменить
        final List<String> warnings;  // риски → работать с осторожностью

        Assessment(String verdict, List<String> critical, List<String> warnings) {
            this.verdict = verdict;
            this.critical = critical;
            this.warnings = warnings;
        }
    }

    /**
     * Есть ли жидкие осадки: по weather[].main или по непустому полю rain.
     * Поле rain приходит как null, если дождя нет, — считать его объектом нельзя.
     */
    static boolean hasPrecipitation(String json) {
        String sky = first(json, "weather");
        String condition = str(sky, "main");
        if (condition != null && LIQUID_PRECIPITATION.contains(condition)) {
            return true;
        }
        // "rain":null → блока нет. "rain":{"1h":0.5} → блок есть.
        return obj(json, "rain") != null;
    }

    /** Оценка погодных условий для выезда бригады / наружных и высотных работ. */
    static Assessment assessConditions(String json) {
        List<String> critical = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String mainBlock = obj(json, "main");
        String windBlock = obj(json, "wind");

        Double temp = number(mainBlock, "temp");
        Double feelsLike = number(mainBlock, "feels_like");
        Double speed = number(windBlock, "speed");
        Double gust = number(windBlock, "gust");
        Double visibility = number(json, "visibility");

        // 1. ГОЛОЛЁД — самое ценное, что видно из ответа. Не «холодно» и не «дождь» по
        //    отдельности, а именно их комбинация: вода на покрытии при температуре около
        //    нуля замерзает. Именно эта связка губит логистику.
        if (temp != null && temp >= ICE_TEMP_MIN && temp <= ICE_TEMP_MAX && hasPrecipitation(json)) {
            critical.add(String.format(NUM,
                    "Высокая вероятность гололёда: %.1f °C и осадки — дорожное покрытие опасно "
                            + "(main.temp в диапазоне %.0f..%.0f °C + weather[].main / rain)",
                    temp, ICE_TEMP_MIN, ICE_TEMP_MAX));
        }

        // 2. Сильный ветер. По правилам охраны труда работы на высоте ограничивают
        //    начиная с 10 м/с, полностью прекращают при 15 м/с и выше.
        if (speed != null && speed >= STRONG_WIND) {
            critical.add(String.format(NUM,
                    "Сильный ветер %.1f м/с — опасно для высотных и наружных работ (wind.speed >= %.0f)",
                    speed, STRONG_WIND));
        }

        // 3. Плохая видимость: туман. Для перевозок это прямой стоп-фактор.
        if (visibility != null && visibility < LOW_VISIBILITY) {
            critical.add(String.format(NUM,
                    "Видимость %.0f м — туман, опасно для перевозок (visibility < %d)",
                    visibility, LOW_VISIBILITY));
        }

        // 4. Заморозки без осадков: не отмена, но риск (обледенение оборудования,
        //    застывание растворов, техника не заводится).
        if (temp != null && temp <= FREEZING_TEMP) {
            warnings.add(String.format(NUM, "Заморозки: %.1f °C (main.temp <= %.0f)", temp, FREEZING_TEMP));
        }

        // 5. Порывы ветра. Средняя скорость может быть приемлемой, а порыв — сорвать
        //    лист профнастила с крыши. Поэтому gust проверяется отдельно от speed.
        if (gust != null && gust >= STRONG_GUST) {
            warnings.add(String.format(NUM, "Порывы ветра до %.1f м/с (wind.gust >= %.0f)", gust, STRONG_GUST));
        }

        // 6. Экстремальная «ощущаемая» температура: время непрерывной работы на улице
        //    надо ограничивать, а перерывы на обогрев — вводить.
        if (feelsLike != null && feelsLike <= EXTREME_FEELS_LIKE) {
            warnings.add(String.format(NUM,
                    "Ощущается как %.1f °C — ограничить время работы на улице (main.feels_like <= %.0f)",
                    feelsLike, EXTREME_FEELS_LIKE));
        }

        String verdict = !critical.isEmpty() ? BLOCK : !warnings.isEmpty() ? REVIEW : ALLOW;
        return new Assessment(verdict, critical, warnings);
    }

    // ── Форматирование ───────────────────────────────────────────────────────

    static final String[] COMPASS = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};

    /** Румб по направлению ветра в градусах (wind.deg). */
    static String compass(double deg) {
        return COMPASS[(int) Math.round(deg / 45) % 8];
    }

    /**
     * dt — Unix timestamp в UTC, timezone — смещение точки в СЕКУНДАХ (10800 = UTC+3).
     * Смещение надо применить руками: сервер не присылает готовое локальное время.
     */
    static String localTime(long dt, int offsetSeconds) {
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);
        String moment = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", NUM)
                .format(Instant.ofEpochSecond(dt).atOffset(offset));
        return String.format(NUM, "%s (местное время, UTC%+d)", moment, offsetSeconds / 3600);
    }

    public static void main(String[] args) throws Exception {
        if (API_KEY.equals(SANDBOX_KEY)) {
            System.out.println("Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n");
        }

        // Дефолт — Москва. Координаты передаются двумя аргументами: java Main.java 59.93 30.33
        double latitude = args.length > 0 ? Double.parseDouble(args[0]) : 55.7558;
        double longitude = args.length > 1 ? Double.parseDouble(args[1]) : 37.6173;

        String json;
        try {
            json = getWeather(latitude, longitude);
        } catch (AtloriumException error) {
            System.err.println("Ошибка: " + error.getMessage());
            System.exit(1);
            return;
        }

        String mainBlock = obj(json, "main");
        String windBlock = obj(json, "wind");
        String sysBlock = obj(json, "sys");
        String cloudsBlock = obj(json, "clouds");
        String coordBlock = obj(json, "coord");
        String sky = first(json, "weather");

        long dt = number(json, "dt").longValue();
        int timezone = number(json, "timezone").intValue();

        System.out.printf(NUM, "Погода по координатам %s, %s%n", latitude, longitude);
        System.out.printf(NUM, "  Точка:        %s (%s), coord из ответа: %s, %s%n",
                str(json, "name"), str(sysBlock, "country"),
                number(coordBlock, "lat"), number(coordBlock, "lon"));
        System.out.printf(NUM, "  Замер:        %s%n", localTime(dt, timezone));
        System.out.printf(NUM, "  Небо:         %s (%s)%n", str(sky, "description"), str(sky, "main"));
        System.out.printf(NUM, "  Температура:  %.1f °C, ощущается как %.1f °C%n",
                number(mainBlock, "temp"), number(mainBlock, "feels_like"));

        Double gust = number(windBlock, "gust");
        String gustText = gust != null ? String.format(NUM, ", порывы до %.1f м/с", gust) : "";
        System.out.printf(NUM, "  Ветер:        %.1f м/с%s, направление %.0f° (%s)%n",
                number(windBlock, "speed"), gustText,
                number(windBlock, "deg"), compass(number(windBlock, "deg")));

        System.out.printf(NUM, "  Влажность:    %.0f %%%n", number(mainBlock, "humidity"));
        System.out.printf(NUM, "  Облачность:   %.0f %%%n", number(cloudsBlock, "all"));

        Double visibility = number(json, "visibility");
        if (visibility != null) {
            System.out.printf(NUM, "  Видимость:    %.0f м%n", visibility);
        }
        System.out.printf(NUM, "  Давление:     %.0f гПа%n", number(mainBlock, "pressure"));

        Assessment assessment = assessConditions(json);
        System.out.printf(NUM, "%nВЕРДИКТ: %s%n", assessment.verdict);
        assessment.critical.forEach(reason -> System.out.println("  [!] " + reason));
        assessment.warnings.forEach(reason -> System.out.println("  [~] " + reason));
        if (ALLOW.equals(assessment.verdict)) {
            System.out.println("  Ограничений по погоде нет.");
        }

        if (API_KEY.equals(SANDBOX_KEY)) {
            System.out.println("\nПесочница: значения генерируются и могут противоречить друг другу и");
            System.out.println("координатам (name не связан с coord, дождь при -14 °C — норма для мока).");
            System.out.println("Проверять логику приложения на них можно, делать выводы о погоде — нет.");
        }
    }
}
