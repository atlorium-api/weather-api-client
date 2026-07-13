/**
 * Клиент API погоды Atlorium — текущая погода по географическим координатам.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   npm install
 *   npm start
 *   npm start -- 59.9343 30.3351
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

/**
 * Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальными
 * данными) — чтобы можно было встроить и протестировать интеграцию до оплаты.
 * Ответы привязаны к округлённым координатам: одна и та же точка всегда даёт одну
 * и ту же «погоду», поэтому на них можно писать стабильные тесты.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const API_KEY = process.env.ATLORIUM_API_KEY ?? SANDBOX_KEY;
const BASE_URL = process.env.ATLORIUM_BASE_URL ?? 'https://atlorium.com';

const TIMEOUT_MS = 30_000;

// Одиночный запуск укладывается в лимит
// с запасом, но если запускать пример в цикле, 429 придёт быстро.
const RETRY_DELAY_MS = 20_000;
const MAX_RETRIES = 1;

// Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно отвечает Retry-After на
// десятки минут — и клиент, слепо доверяющий заголовку, «зависнет» на всё это время
// (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
const MAX_RETRY_DELAY_MS = 120_000;

// ── Пороги принятия решения ───────────────────────────────────────────────────
// Вынесены в константы намеренно: у логистики, кровельщиков и монтажников высотных
// конструкций пороги разные. Меняется здесь, а не по всему коду.

const FREEZING_TEMP = 0; //       °C, main.temp: ниже — заморозки
const ICE_TEMP_MIN = -5; //       °C, нижняя граница «гололёдного окна»
const ICE_TEMP_MAX = 3; //        °C, верхняя граница «гололёдного окна»
const STRONG_WIND = 10; //        м/с, wind.speed: правила охраны труда на высоте
const STRONG_GUST = 15; //        м/с, wind.gust: порывы
const LOW_VISIBILITY = 1000; //   м, visibility: туман
const EXTREME_FEELS_LIKE = -20; // °C, main.feels_like: переохлаждение

/** Осадки, дающие жидкую воду на дорожном покрытии (weather[].main). */
const LIQUID_PRECIPITATION = new Set(['Rain', 'Drizzle']);

// ── Модель ответа (snake_case, °C, м/с) ─────────────────

/** Состояние неба. `main` — машинный код: Clear | Clouds | Rain | Snow | Fog | Drizzle. */
export interface Condition {
  id: number;
  main: string;
  description: string;
  icon: string;
}

export interface MainBlock {
  temp: number;
  feels_like: number;
  pressure: number;
  humidity: number;
  temp_min: number;
  temp_max: number;
  sea_level: number | null;
  grnd_level: number | null;
}

export interface Wind {
  speed: number;
  deg: number;
  /** Порывы. Отсутствуют при штиле — поэтому nullable. */
  gust: number | null;
}

/** Осадки за 1 и 3 часа, мм. Приходит null, если осадков нет. */
export interface Precipitation {
  '1h': number | null;
  '3h': number | null;
}

export interface Sys {
  type: number | null;
  id: number | null;
  message: number | null;
  country: string | null;
  sunrise: number;
  sunset: number;
}

export interface Weather {
  coord: { lat: number; lon: number };
  weather: Condition[];
  base: string;
  main: MainBlock;
  /** Видимость в метрах, максимум 10000. */
  visibility: number | null;
  wind: Wind;
  clouds: { all: number };
  rain: Precipitation | null;
  snow: Precipitation | null;
  /** Время замера, Unix timestamp в UTC. */
  dt: number;
  sys: Sys;
  /** Смещение точки от UTC в СЕКУНДАХ: 10800 = UTC+3. */
  timezone: number;
  id: number;
  /** Название населённого пункта. */
  name: string;
  cod: number;
}

const ERROR_REASONS: Record<number, string> = {
  400: 'Координаты вне диапазона (широта -90..90, долгота -180..180)',
  401: 'API-ключ отсутствует, просрочен или недействителен',
  402: 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
  429: 'Превышен лимит запросов — повторите позже',
  500: 'Внутренняя ошибка при получении погодных данных',
  503: 'Погодный сервис временно недоступен (за сбой на своей стороне мы не списываем деньги)',
};

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
export class AtloriumError extends Error {
  constructor(readonly status: number, body: string) {
    const reason = ERROR_REASONS[status] ?? 'Неизвестная ошибка';
    super(`HTTP ${status}: ${reason}. Ответ сервера: ${body.slice(0, 200)}`);
    this.name = 'AtloriumError';
  }
}

/**
 * Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
 * 0 означал бы busy-loop, а десятки минут (так сервер отвечает на исчерпанный часовой
 * лимит) — зависание клиента. Возвращаем 0, если ждать бессмысленно долго.
 */
function retryAfterMs(response: Response): number {
  const seconds = Number.parseInt(response.headers.get('Retry-After') ?? '', 10);
  if (!Number.isFinite(seconds) || seconds <= 0) return RETRY_DELAY_MS;
  const delay = seconds * 1000;
  return delay <= MAX_RETRY_DELAY_MS ? delay : 0;
}

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Текущая погода в точке: GET /api/Weather?latitude=..&longitude=..
 *
 * Ответ: температура в °C, скорость ветра в м/с,
 * dt — Unix timestamp (UTC), timezone — смещение точки в СЕКУНДАХ.
 */
export async function getWeather(latitude: number, longitude: number): Promise<Weather> {
  const url = new URL('/api/Weather', BASE_URL);
  url.searchParams.set('latitude', String(latitude));
  url.searchParams.set('longitude', String(longitude));

  for (let attempt = 0; ; attempt++) {
    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${API_KEY}`,
        Accept: 'application/json',
      },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });

    // 429 — не поломка, а реальный лимит продукта.
    if (response.status === 429 && attempt < MAX_RETRIES) {
      const delay = retryAfterMs(response);
      if (delay === 0) {
        throw new AtloriumError(429, 'лимит по IP исчерпан надолго, повторите позже');
      }
      console.error(`  ... лимит запросов, пауза ${delay / 1000} с`);
      await sleep(delay);
      continue;
    }

    if (!response.ok) {
      throw new AtloriumError(response.status, await response.text());
    }
    return (await response.json()) as Weather;
  }
}

// ── Применение данных: допуск бригады к работам ───────────────────────────────
// Погода сама по себе — просто JSON. Ценность появляется, когда по ней принимают
// решение: выпускать машину на маршрут, поднимать людей на высоту, начинать
// наружные работы — или отменять. Ниже — ровно это решение.

export const ALLOW = 'МОЖНО РАБОТАТЬ';
export const REVIEW = 'РАБОТАТЬ С ОСТОРОЖНОСТЬЮ';
export const BLOCK = 'РАБОТЫ ОТМЕНИТЬ';

export interface Assessment {
  verdict: string;
  /** Стоп-факторы → работы отменить. */
  critical: string[];
  /** Риски → работать с осторожностью. */
  warnings: string[];
}

/**
 * Есть ли жидкие осадки: по weather[].main или по непустому полю rain.
 * Поле rain приходит как null, если дождя нет, — обращаться к rain['1h'] без
 * проверки нельзя.
 */
function hasPrecipitation(weather: Weather): boolean {
  if (weather.weather?.some((condition) => LIQUID_PRECIPITATION.has(condition.main))) {
    return true;
  }
  return weather.rain !== null && weather.rain !== undefined;
}

/** Оценка погодных условий для выезда бригады / наружных и высотных работ. */
export function assessConditions(weather: Weather): Assessment {
  const critical: string[] = [];
  const warnings: string[] = [];

  const { temp, feels_like: feelsLike } = weather.main;
  const { speed, gust } = weather.wind;
  const { visibility } = weather;

  // 1. ГОЛОЛЁД — самое ценное, что видно из ответа. Не «холодно» и не «дождь» по
  //    отдельности, а именно их комбинация: вода на покрытии при температуре около
  //    нуля замерзает. Именно эта связка губит логистику.
  if (temp >= ICE_TEMP_MIN && temp <= ICE_TEMP_MAX && hasPrecipitation(weather)) {
    critical.push(
      `Высокая вероятность гололёда: ${temp.toFixed(1)} °C и осадки — дорожное покрытие опасно ` +
        `(main.temp в диапазоне ${ICE_TEMP_MIN}..${ICE_TEMP_MAX} °C + weather[].main / rain)`,
    );
  }

  // 2. Сильный ветер. По правилам охраны труда работы на высоте ограничивают
  //    начиная с 10 м/с, полностью прекращают при 15 м/с и выше.
  if (speed >= STRONG_WIND) {
    critical.push(
      `Сильный ветер ${speed.toFixed(1)} м/с — опасно для высотных и наружных работ ` +
        `(wind.speed >= ${STRONG_WIND})`,
    );
  }

  // 3. Плохая видимость: туман. Для перевозок это прямой стоп-фактор.
  if (visibility !== null && visibility < LOW_VISIBILITY) {
    critical.push(
      `Видимость ${visibility} м — туман, опасно для перевозок (visibility < ${LOW_VISIBILITY})`,
    );
  }

  // 4. Заморозки без осадков: не отмена, но риск (обледенение оборудования,
  //    застывание растворов, техника не заводится).
  if (temp <= FREEZING_TEMP) {
    warnings.push(`Заморозки: ${temp.toFixed(1)} °C (main.temp <= ${FREEZING_TEMP})`);
  }

  // 5. Порывы ветра. Средняя скорость может быть приемлемой, а порыв — сорвать
  //    лист профнастила с крыши. Поэтому gust проверяется отдельно от speed.
  if (gust !== null && gust >= STRONG_GUST) {
    warnings.push(`Порывы ветра до ${gust.toFixed(1)} м/с (wind.gust >= ${STRONG_GUST})`);
  }

  // 6. Экстремальная «ощущаемая» температура: время непрерывной работы на улице
  //    надо ограничивать, а перерывы на обогрев — вводить.
  if (feelsLike <= EXTREME_FEELS_LIKE) {
    warnings.push(
      `Ощущается как ${feelsLike.toFixed(1)} °C — ограничить время работы на улице ` +
        `(main.feels_like <= ${EXTREME_FEELS_LIKE})`,
    );
  }

  const verdict = critical.length > 0 ? BLOCK : warnings.length > 0 ? REVIEW : ALLOW;
  return { verdict, critical, warnings };
}

// ── Форматирование ────────────────────────────────────────────────────────────

const COMPASS = ['С', 'СВ', 'В', 'ЮВ', 'Ю', 'ЮЗ', 'З', 'СЗ'] as const;

/** Румб по направлению ветра в градусах (wind.deg). */
export function compass(deg: number): string {
  return COMPASS[Math.round(deg / 45) % 8] ?? '?';
}

/**
 * dt — Unix timestamp в UTC, timezone — смещение точки в СЕКУНДАХ (10800 = UTC+3).
 * Смещение надо применить руками: сервер не присылает готовое локальное время.
 */
export function localTime(dt: number, offsetSeconds: number): string {
  const shifted = new Date((dt + offsetSeconds) * 1000);
  const pad = (value: number): string => String(value).padStart(2, '0');
  const date =
    `${pad(shifted.getUTCDate())}.${pad(shifted.getUTCMonth() + 1)}.${shifted.getUTCFullYear()}`;
  const time = `${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}`;
  const hours = Math.trunc(offsetSeconds / 3600);
  const sign = hours >= 0 ? '+' : '-';
  return `${date} ${time} (местное время, UTC${sign}${Math.abs(hours)})`;
}

async function main(): Promise<void> {
  if (API_KEY === SANDBOX_KEY) {
    console.log('Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n');
  }

  // Дефолт — Москва. Координаты передаются двумя аргументами: npm start -- 59.93 30.33
  const latitude = Number(process.argv[2] ?? 55.7558);
  const longitude = Number(process.argv[3] ?? 37.6173);

  const weather = await getWeather(latitude, longitude);
  const sky = weather.weather[0];

  console.log(`Погода по координатам ${latitude}, ${longitude}`);
  console.log(
    `  Точка:        ${weather.name} (${weather.sys.country}), ` +
      `coord из ответа: ${weather.coord.lat}, ${weather.coord.lon}`,
  );
  console.log(`  Замер:        ${localTime(weather.dt, weather.timezone)}`);
  console.log(`  Небо:         ${sky?.description} (${sky?.main})`);
  console.log(
    `  Температура:  ${weather.main.temp.toFixed(1)} °C, ` +
      `ощущается как ${weather.main.feels_like.toFixed(1)} °C`,
  );

  const gust = weather.wind.gust;
  const gustText = gust !== null ? `, порывы до ${gust.toFixed(1)} м/с` : '';
  console.log(
    `  Ветер:        ${weather.wind.speed.toFixed(1)} м/с${gustText}, ` +
      `направление ${weather.wind.deg}° (${compass(weather.wind.deg)})`,
  );

  console.log(`  Влажность:    ${weather.main.humidity} %`);
  console.log(`  Облачность:   ${weather.clouds.all} %`);

  // visibility может не прийти — печатаем только когда есть.
  if (weather.visibility !== null) {
    console.log(`  Видимость:    ${weather.visibility} м`);
  }

  console.log(`  Давление:     ${weather.main.pressure} гПа`);

  const assessment = assessConditions(weather);
  console.log(`\nВЕРДИКТ: ${assessment.verdict}`);
  assessment.critical.forEach((reason) => console.log(`  [!] ${reason}`));
  assessment.warnings.forEach((reason) => console.log(`  [~] ${reason}`));
  if (assessment.verdict === ALLOW) {
    console.log('  Ограничений по погоде нет.');
  }

  if (API_KEY === SANDBOX_KEY) {
    console.log('\nПесочница: значения генерируются и могут противоречить друг другу и');
    console.log('координатам (name не связан с coord, дождь при -14 °C — норма для мока).');
    console.log('Проверять логику приложения на них можно, делать выводы о погоде — нет.');
  }
}

// Запуск только когда файл выполняется напрямую, а не импортируется.
if (process.argv[1]?.includes('index')) {
  main().catch((error: unknown) => {
    console.error('Ошибка:', error instanceof Error ? error.message : error);
    process.exit(1);
  });
}
