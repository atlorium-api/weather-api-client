"""
Клиент API погоды Atlorium — текущая погода по географическим координатам.

Запуск (работает сразу, без регистрации — на демо-ключе):
    pip install -r requirements.txt
    python main.py
    python main.py 59.9343 30.3351

Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
ATLORIUM_API_KEY. Код при этом не меняется.
"""

import os
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

import requests

# Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальными
# данными) — чтобы можно было встроить и протестировать интеграцию до оплаты.
# Ответы привязаны к округлённым координатам: одна и та же точка всегда даёт
# одну и ту же «погоду», поэтому на них можно писать стабильные тесты.
SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1"

API_KEY = os.environ.get("ATLORIUM_API_KEY", SANDBOX_KEY)
BASE_URL = os.environ.get("ATLORIUM_BASE_URL", "https://atlorium.com")

TIMEOUT = 30

# Одиночный запуск укладывается в лимит
# с запасом, но если запускать пример в цикле, 429 придёт быстро.
RETRY_DELAY = 20
MAX_RETRIES = 1

# Потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно отвечает Retry-After на
# десятки минут — и клиент, слепо доверяющий заголовку, «зависнет» на всё это время
# (а в CI просто съест бюджет джоба). Дольше потолка не ждём: честно сообщаем, что
# квота исчерпана, и выходим.
MAX_RETRY_DELAY = 120

# ── Пороги принятия решения ───────────────────────────────────────────────────
# Вынесены в константы намеренно: у логистики, кровельщиков и монтажников высотных
# конструкций пороги разные. Меняется здесь, а не по всему коду.

FREEZING_TEMP = 0.0        # °C, main.temp: ниже — заморозки
ICE_TEMP_MIN = -5.0        # °C, нижняя граница «гололёдного окна»
ICE_TEMP_MAX = 3.0         # °C, верхняя граница «гололёдного окна»
STRONG_WIND = 10.0         # м/с, wind.speed: правила охраны труда на высоте
STRONG_GUST = 15.0         # м/с, wind.gust: порывы
LOW_VISIBILITY = 1000      # м, visibility: туман
EXTREME_FEELS_LIKE = -20.0 # °C, main.feels_like: переохлаждение

# Осадки, дающие жидкую воду на дорожном покрытии (weather[].main).
LIQUID_PRECIPITATION = {"Rain", "Drizzle"}


class AtloriumError(RuntimeError):
    """Ошибка API. Код HTTP разложен в человекочитаемую причину."""

    REASONS = {
        400: "Координаты вне диапазона (широта -90..90, долгота -180..180)",
        401: "API-ключ отсутствует, просрочен или недействителен",
        402: "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        429: "Превышен лимит запросов — повторите позже",
        500: "Внутренняя ошибка при получении погодных данных",
        503: "Погодный сервис временно недоступен "
             "(за сбой на своей стороне мы не списываем деньги)",
    }

    def __init__(self, status: int, body: str):
        reason = self.REASONS.get(status, "Неизвестная ошибка")
        super().__init__(f"HTTP {status}: {reason}. Ответ сервера: {body[:200]}")
        self.status = status


def _retry_after(response: requests.Response) -> int:
    """Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру.

    Значение 0 (или мусор) означало бы «повторяй немедленно» — клиент ушёл бы в
    busy-loop. Значение в десятки минут (так сервер отвечает на исчерпанный часовой
    лимит) означало бы «спи почти час». Возвращаем 0, если ждать бессмысленно долго:
    вызывающий сдастся.
    """
    try:
        seconds = int(response.headers.get("Retry-After", ""))
    except ValueError:
        seconds = 0

    if seconds <= 0:
        return RETRY_DELAY
    return seconds if seconds <= MAX_RETRY_DELAY else 0


def get_weather(latitude: float, longitude: float) -> dict:
    """Текущая погода в точке: GET /api/Weather?latitude=..&longitude=..

    Ответ: температура в °C, скорость ветра в м/с,
    dt — Unix timestamp (UTC), timezone — смещение точки в СЕКУНДАХ.
    """
    for attempt in range(MAX_RETRIES + 1):
        response = requests.get(
            f"{BASE_URL}/api/Weather",
            params={"latitude": latitude, "longitude": longitude},
            headers={
                "Authorization": f"Bearer {API_KEY}",
                "Accept": "application/json",
            },
            timeout=TIMEOUT,
        )

        # 429 — не поломка, а реальный лимит продукта.
        if response.status_code == 429 and attempt < MAX_RETRIES:
            delay = _retry_after(response)
            if delay == 0:
                raise AtloriumError(429, "лимит по IP исчерпан надолго, повторите позже")
            print(f"  ... лимит запросов, пауза {delay} с", file=sys.stderr)
            time.sleep(delay)
            continue

        if not response.ok:
            raise AtloriumError(response.status_code, response.text)
        return response.json()

    raise AtloriumError(429, "лимит запросов не отпустил после повтора")


# ── Применение данных: допуск бригады к работам ───────────────────────────────
# Погода сама по себе — просто JSON. Ценность появляется, когда по ней принимают
# решение: выпускать машину на маршрут, поднимать людей на высоту, начинать
# наружные работы — или отменять. Ниже — ровно это решение.

ALLOW = "МОЖНО РАБОТАТЬ"
REVIEW = "РАБОТАТЬ С ОСТОРОЖНОСТЬЮ"
BLOCK = "РАБОТЫ ОТМЕНИТЬ"


@dataclass
class Assessment:
    verdict: str = ALLOW
    critical: list[str] = field(default_factory=list)  # → отмена
    warnings: list[str] = field(default_factory=list)  # → осторожность


def _has_precipitation(weather: dict) -> bool:
    """Есть ли жидкие осадки: по weather[].main или по непустому полю rain.

    Поле rain приходит как null, если дождя нет — обращаться к rain["1h"] без
    проверки нельзя, получите TypeError.
    """
    conditions = weather.get("weather") or []
    if any((c.get("main") or "") in LIQUID_PRECIPITATION for c in conditions):
        return True
    return bool(weather.get("rain"))


def assess_conditions(weather: dict) -> Assessment:
    """Оценка погодных условий для выезда бригады / наружных и высотных работ."""
    assessment = Assessment()

    main = weather.get("main") or {}
    wind = weather.get("wind") or {}

    temp = main.get("temp")
    feels_like = main.get("feels_like")
    speed = wind.get("speed")
    gust = wind.get("gust")          # может отсутствовать — при штиле порывов нет
    visibility = weather.get("visibility")

    # 1. ГОЛОЛЁД — самое ценное, что видно из ответа. Не «холодно» и не «дождь» по
    #    отдельности, а именно их комбинация: вода на покрытии при температуре около
    #    нуля замерзает. Именно эта связка губит логистику.
    if (
        temp is not None
        and ICE_TEMP_MIN <= temp <= ICE_TEMP_MAX
        and _has_precipitation(weather)
    ):
        assessment.critical.append(
            f"Высокая вероятность гололёда: {temp:.1f} °C и осадки — дорожное покрытие "
            f"опасно (main.temp в диапазоне {ICE_TEMP_MIN:.0f}..{ICE_TEMP_MAX:.0f} °C "
            f"+ weather[].main / rain)"
        )

    # 2. Сильный ветер. По правилам охраны труда работы на высоте ограничивают
    #    начиная с 10 м/с, полностью прекращают при 15 м/с и выше.
    if speed is not None and speed >= STRONG_WIND:
        assessment.critical.append(
            f"Сильный ветер {speed:.1f} м/с — опасно для высотных и наружных работ "
            f"(wind.speed >= {STRONG_WIND:.0f})"
        )

    # 3. Плохая видимость: туман. Для перевозок это прямой стоп-фактор.
    if visibility is not None and visibility < LOW_VISIBILITY:
        assessment.critical.append(
            f"Видимость {visibility} м — туман, опасно для перевозок "
            f"(visibility < {LOW_VISIBILITY})"
        )

    # 4. Заморозки без осадков: не отмена, но риск (обледенение оборудования,
    #    застывание растворов, техника не заводится).
    if temp is not None and temp <= FREEZING_TEMP:
        assessment.warnings.append(
            f"Заморозки: {temp:.1f} °C (main.temp <= {FREEZING_TEMP:.0f})"
        )

    # 5. Порывы ветра. Средняя скорость может быть приемлемой, а порыв — сорвать
    #    лист профнастила с крыши. Поэтому gust проверяется отдельно от speed.
    if gust is not None and gust >= STRONG_GUST:
        assessment.warnings.append(
            f"Порывы ветра до {gust:.1f} м/с (wind.gust >= {STRONG_GUST:.0f})"
        )

    # 6. Экстремальная «ощущаемая» температура: время непрерывной работы на улице
    #    надо ограничивать, а перерывы на обогрев — вводить.
    if feels_like is not None and feels_like <= EXTREME_FEELS_LIKE:
        assessment.warnings.append(
            f"Ощущается как {feels_like:.1f} °C — ограничить время работы на улице "
            f"(main.feels_like <= {EXTREME_FEELS_LIKE:.0f})"
        )

    if assessment.critical:
        assessment.verdict = BLOCK
    elif assessment.warnings:
        assessment.verdict = REVIEW

    return assessment


# ── Форматирование ────────────────────────────────────────────────────────────

COMPASS = ["С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"]


def compass(deg: int | None) -> str:
    """Румб по направлению ветра в градусах (wind.deg)."""
    if deg is None:
        return "?"
    return COMPASS[round(deg / 45) % 8]


def local_time(dt: int, offset_seconds: int) -> str:
    """dt — Unix timestamp в UTC, timezone — смещение точки в СЕКУНДАХ (10800 = UTC+3).

    Смещение надо применить руками: сервер не присылает готовое локальное время.
    """
    tz = timezone(timedelta(seconds=offset_seconds))
    moment = datetime.fromtimestamp(dt, tz)
    hours = offset_seconds // 3600
    return f"{moment:%d.%m.%Y %H:%M} (местное время, UTC{hours:+d})"


def main() -> int:
    if API_KEY == SANDBOX_KEY:
        print("Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n")

    # Дефолт — Москва. Координаты передаются двумя аргументами: python main.py 59.93 30.33
    latitude = float(sys.argv[1]) if len(sys.argv) > 1 else 55.7558
    longitude = float(sys.argv[2]) if len(sys.argv) > 2 else 37.6173

    try:
        weather = get_weather(latitude, longitude)
    except AtloriumError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1

    main_block = weather.get("main") or {}
    wind = weather.get("wind") or {}
    clouds = weather.get("clouds") or {}
    sys_block = weather.get("sys") or {}
    conditions = weather.get("weather") or []
    sky = conditions[0] if conditions else {}
    coord = weather.get("coord") or {}

    print(f"Погода по координатам {latitude}, {longitude}")
    print(f"  Точка:        {weather.get('name')} ({sys_block.get('country')}), "
          f"coord из ответа: {coord.get('lat')}, {coord.get('lon')}")
    print(f"  Замер:        {local_time(weather['dt'], weather['timezone'])}")
    print(f"  Небо:         {sky.get('description')} ({sky.get('main')})")
    print(f"  Температура:  {main_block.get('temp'):.1f} °C, "
          f"ощущается как {main_block.get('feels_like'):.1f} °C")

    gust = wind.get("gust")
    gust_text = f", порывы до {gust:.1f} м/с" if gust is not None else ""
    print(f"  Ветер:        {wind.get('speed'):.1f} м/с{gust_text}, "
          f"направление {wind.get('deg')}° ({compass(wind.get('deg'))})")

    print(f"  Влажность:    {main_block.get('humidity')} %")
    print(f"  Облачность:   {clouds.get('all')} %")

    # visibility может не прийти — печатаем только когда есть.
    visibility = weather.get("visibility")
    if visibility is not None:
        print(f"  Видимость:    {visibility} м")

    print(f"  Давление:     {main_block.get('pressure')} гПа")

    assessment = assess_conditions(weather)
    print(f"\nВЕРДИКТ: {assessment.verdict}")
    for reason in assessment.critical:
        print(f"  [!] {reason}")
    for reason in assessment.warnings:
        print(f"  [~] {reason}")
    if assessment.verdict == ALLOW:
        print("  Ограничений по погоде нет.")

    if API_KEY == SANDBOX_KEY:
        print("\nПесочница: значения генерируются и могут противоречить друг другу и")
        print("координатам (name не связан с coord, дождь при -14 °C — норма для мока).")
        print("Проверять логику приложения на них можно, делать выводы о погоде — нет.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
