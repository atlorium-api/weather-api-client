// Клиент API погоды Atlorium — текущая погода по географическим координатам.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//
//	go run .
//	go run . 59.9343 30.3351
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"time"
)

// SandboxKey — публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ
// (не реальными данными), чтобы можно было встроить интеграцию до оплаты.
// Ответы привязаны к округлённым координатам — на них можно писать стабильные тесты.
const SandboxKey = "ak_sandbox_demo_mockdata_v1"

// Одиночный запуск укладывается в лимит
// с запасом, но если запускать пример в цикле, 429 придёт быстро.
//
// MaxRetryDelay — потолок ожидания. Исчерпав ЧАСОВОЙ лимит, сервер честно отвечает
// Retry-After на десятки минут, и клиент, слепо доверяющий заголовку, «зависнет» на
// всё это время (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
const (
	RetryDelay    = 20 * time.Second
	MaxRetries    = 1
	MaxRetryDelay = 120 * time.Second
)

// Пороги принятия решения. Вынесены в константы намеренно: у логистики, кровельщиков
// и монтажников высотных конструкций пороги разные.
const (
	FreezingTemp     = 0.0   // °C, main.temp: ниже — заморозки
	IceTempMin       = -5.0  // °C, нижняя граница «гололёдного окна»
	IceTempMax       = 3.0   // °C, верхняя граница «гололёдного окна»
	StrongWind       = 10.0  // м/с, wind.speed: правила охраны труда на высоте
	StrongGust       = 15.0  // м/с, wind.gust: порывы
	LowVisibility    = 1000  // м, visibility: туман
	ExtremeFeelsLike = -20.0 // °C, main.feels_like: переохлаждение
)

var (
	apiKey  = envOr("ATLORIUM_API_KEY", SandboxKey)
	baseURL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com")
	client  = &http.Client{Timeout: 30 * time.Second}

	// Осадки, дающие жидкую воду на дорожном покрытии (weather[].main).
	liquidPrecipitation = map[string]bool{"Rain": true, "Drizzle": true}
)

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

// ── Модель ответа (snake_case, °C, м/с) ─────────────────

// Coord — координаты точки. Сервер возвращает ровно то, что прислали в запросе.
type Coord struct {
	Lon float64 `json:"lon"`
	Lat float64 `json:"lat"`
}

// Condition — состояние неба. Main — машинный код: Clear | Clouds | Rain | Snow | Fog | Drizzle.
type Condition struct {
	ID          int    `json:"id"`
	Main        string `json:"main"`
	Description string `json:"description"`
	Icon        string `json:"icon"`
}

// MainBlock — температура, давление, влажность.
type MainBlock struct {
	Temp      float64 `json:"temp"`
	FeelsLike float64 `json:"feels_like"`
	Pressure  int     `json:"pressure"`
	Humidity  int     `json:"humidity"`
	TempMin   float64 `json:"temp_min"`
	TempMax   float64 `json:"temp_max"`
	SeaLevel  *int    `json:"sea_level"`
	GrndLevel *int    `json:"grnd_level"`
}

// Wind — ветер. Gust (порывы) отсутствует при штиле, поэтому указатель.
type Wind struct {
	Speed float64  `json:"speed"`
	Deg   int      `json:"deg"`
	Gust  *float64 `json:"gust"`
}

// Precipitation — осадки за 1 и 3 часа, мм. Поле приходит null, если осадков нет.
type Precipitation struct {
	OneHour    *float64 `json:"1h"`
	ThreeHours *float64 `json:"3h"`
}

// Sys — служебный блок: страна, восход, закат.
type Sys struct {
	Type    *int     `json:"type"`
	ID      *int     `json:"id"`
	Message *float64 `json:"message"`
	Country string   `json:"country"`
	Sunrise int64    `json:"sunrise"`
	Sunset  int64    `json:"sunset"`
}

// Weather — полный ответ сервиса.
type Weather struct {
	Coord      Coord          `json:"coord"`
	Weather    []Condition    `json:"weather"`
	Base       string         `json:"base"`
	Main       MainBlock      `json:"main"`
	Visibility *int           `json:"visibility"` // метры, максимум 10000
	Wind       Wind           `json:"wind"`
	Clouds     struct {
		All int `json:"all"` // облачность в процентах
	} `json:"clouds"`
	Rain *Precipitation `json:"rain"`
	Snow *Precipitation `json:"snow"`
	Dt   int64          `json:"dt"` // время замера, Unix timestamp в UTC
	Sys  Sys            `json:"sys"`
	// Timezone — смещение точки от UTC в СЕКУНДАХ: 10800 = UTC+3.
	Timezone int    `json:"timezone"`
	ID       int    `json:"id"`
	Name     string `json:"name"` // название населённого пункта
	Cod      int    `json:"cod"`
}

// APIError раскладывает HTTP-код в человекочитаемую причину.
type APIError struct {
	Status int
	Body   string
}

func (e *APIError) Error() string {
	reasons := map[int]string{
		400: "координаты вне диапазона (широта -90..90, долгота -180..180)",
		401: "API-ключ отсутствует, просрочен или недействителен",
		402: "недостаточно кредитов на балансе — пополните на https://atlorium.com",
		429: "превышен лимит запросов — повторите позже",
		500: "внутренняя ошибка при получении погодных данных",
		503: "погодный сервис временно недоступен (за сбой на своей стороне мы не списываем деньги)",
	}
	reason, ok := reasons[e.Status]
	if !ok {
		reason = "неизвестная ошибка"
	}
	return fmt.Sprintf("HTTP %d: %s. Ответ сервера: %s", e.Status, reason, e.Body)
}

// retryAfter сообщает, сколько ждать после 429. Ноль/мусор и слишком большие значения
// не берём на веру: 0 означал бы busy-loop, а десятки минут (так сервер отвечает на
// исчерпанный часовой лимит) — зависание клиента. Ноль означает «ждать бессмысленно».
func retryAfter(response *http.Response) time.Duration {
	seconds, err := strconv.Atoi(response.Header.Get("Retry-After"))
	if err != nil || seconds <= 0 {
		return RetryDelay
	}
	if delay := time.Duration(seconds) * time.Second; delay <= MaxRetryDelay {
		return delay
	}
	return 0
}

// GetWeather возвращает текущую погоду в точке: GET /api/Weather?latitude=..&longitude=..
//
// Ответ: температура в °C, скорость ветра в м/с,
// dt — Unix timestamp (UTC), timezone — смещение точки в СЕКУНДАХ.
func GetWeather(latitude, longitude float64) (*Weather, error) {
	query := url.Values{
		"latitude":  {strconv.FormatFloat(latitude, 'f', -1, 64)},
		"longitude": {strconv.FormatFloat(longitude, 'f', -1, 64)},
	}
	endpoint := baseURL + "/api/Weather?" + query.Encode()

	for attempt := 0; ; attempt++ {
		request, err := http.NewRequest(http.MethodGet, endpoint, nil)
		if err != nil {
			return nil, err
		}
		request.Header.Set("Authorization", "Bearer "+apiKey)
		request.Header.Set("Accept", "application/json")

		response, err := client.Do(request)
		if err != nil {
			return nil, err
		}

		// 429 — не поломка, а реальный лимит продукта.
		if response.StatusCode == http.StatusTooManyRequests && attempt < MaxRetries {
			delay := retryAfter(response)
			response.Body.Close()
			if delay == 0 {
				return nil, &APIError{Status: 429, Body: "лимит по IP исчерпан надолго, повторите позже"}
			}
			fmt.Fprintf(os.Stderr, "  ... лимит запросов, пауза %s\n", delay)
			time.Sleep(delay)
			continue
		}

		body, err := io.ReadAll(response.Body)
		response.Body.Close()
		if err != nil {
			return nil, err
		}
		if response.StatusCode != http.StatusOK {
			return nil, &APIError{Status: response.StatusCode, Body: string(body)}
		}

		var weather Weather
		if err := json.Unmarshal(body, &weather); err != nil {
			return nil, err
		}
		return &weather, nil
	}
}

// ── Применение данных: допуск бригады к работам ───────────────────────────────
// Погода сама по себе — просто JSON. Ценность появляется, когда по ней принимают
// решение: выпускать машину на маршрут, поднимать людей на высоту, начинать
// наружные работы — или отменять. Ниже — ровно это решение.

const (
	Allow  = "МОЖНО РАБОТАТЬ"
	Review = "РАБОТАТЬ С ОСТОРОЖНОСТЬЮ"
	Block  = "РАБОТЫ ОТМЕНИТЬ"
)

// Assessment — вердикт и причины, каждая со ссылкой на поле ответа.
type Assessment struct {
	Verdict  string
	Critical []string // стоп-факторы → работы отменить
	Warnings []string // риски → работать с осторожностью
}

// hasPrecipitation сообщает, есть ли жидкие осадки: по weather[].main или по
// непустому полю rain. Поле rain приходит как null, если дождя нет, — разыменовывать
// его без проверки нельзя, получите панику.
func hasPrecipitation(w *Weather) bool {
	for _, condition := range w.Weather {
		if liquidPrecipitation[condition.Main] {
			return true
		}
	}
	return w.Rain != nil
}

// AssessConditions оценивает погодные условия для выезда бригады, наружных
// и высотных работ.
func AssessConditions(w *Weather) Assessment {
	var assessment Assessment

	// 1. ГОЛОЛЁД — самое ценное, что видно из ответа. Не «холодно» и не «дождь» по
	//    отдельности, а именно их комбинация: вода на покрытии при температуре около
	//    нуля замерзает. Именно эта связка губит логистику.
	if w.Main.Temp >= IceTempMin && w.Main.Temp <= IceTempMax && hasPrecipitation(w) {
		assessment.Critical = append(assessment.Critical, fmt.Sprintf(
			"Высокая вероятность гололёда: %.1f °C и осадки — дорожное покрытие опасно "+
				"(main.temp в диапазоне %.0f..%.0f °C + weather[].main / rain)",
			w.Main.Temp, IceTempMin, IceTempMax))
	}

	// 2. Сильный ветер. По правилам охраны труда работы на высоте ограничивают
	//    начиная с 10 м/с, полностью прекращают при 15 м/с и выше.
	if w.Wind.Speed >= StrongWind {
		assessment.Critical = append(assessment.Critical, fmt.Sprintf(
			"Сильный ветер %.1f м/с — опасно для высотных и наружных работ (wind.speed >= %.0f)",
			w.Wind.Speed, StrongWind))
	}

	// 3. Плохая видимость: туман. Для перевозок это прямой стоп-фактор.
	if w.Visibility != nil && *w.Visibility < LowVisibility {
		assessment.Critical = append(assessment.Critical, fmt.Sprintf(
			"Видимость %d м — туман, опасно для перевозок (visibility < %d)",
			*w.Visibility, LowVisibility))
	}

	// 4. Заморозки без осадков: не отмена, но риск (обледенение оборудования,
	//    застывание растворов, техника не заводится).
	if w.Main.Temp <= FreezingTemp {
		assessment.Warnings = append(assessment.Warnings, fmt.Sprintf(
			"Заморозки: %.1f °C (main.temp <= %.0f)", w.Main.Temp, FreezingTemp))
	}

	// 5. Порывы ветра. Средняя скорость может быть приемлемой, а порыв — сорвать
	//    лист профнастила с крыши. Поэтому gust проверяется отдельно от speed.
	if w.Wind.Gust != nil && *w.Wind.Gust >= StrongGust {
		assessment.Warnings = append(assessment.Warnings, fmt.Sprintf(
			"Порывы ветра до %.1f м/с (wind.gust >= %.0f)", *w.Wind.Gust, StrongGust))
	}

	// 6. Экстремальная «ощущаемая» температура: время непрерывной работы на улице
	//    надо ограничивать, а перерывы на обогрев — вводить.
	if w.Main.FeelsLike <= ExtremeFeelsLike {
		assessment.Warnings = append(assessment.Warnings, fmt.Sprintf(
			"Ощущается как %.1f °C — ограничить время работы на улице (main.feels_like <= %.0f)",
			w.Main.FeelsLike, ExtremeFeelsLike))
	}

	switch {
	case len(assessment.Critical) > 0:
		assessment.Verdict = Block
	case len(assessment.Warnings) > 0:
		assessment.Verdict = Review
	default:
		assessment.Verdict = Allow
	}
	return assessment
}

// ── Форматирование ────────────────────────────────────────────────────────────

var compassPoints = [8]string{"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"}

// Compass возвращает румб по направлению ветра в градусах (wind.deg).
func Compass(deg int) string {
	index := int(math.Round(float64(deg)/45)) % 8
	return compassPoints[index]
}

// LocalTime переводит dt (Unix timestamp в UTC) в местное время точки.
// timezone — смещение в СЕКУНДАХ (10800 = UTC+3), применить его надо руками:
// сервер не присылает готовое локальное время.
func LocalTime(dt int64, offsetSeconds int) string {
	zone := time.FixedZone("", offsetSeconds)
	moment := time.Unix(dt, 0).In(zone)
	return fmt.Sprintf("%s (местное время, UTC%+d)",
		moment.Format("02.01.2006 15:04"), offsetSeconds/3600)
}

func main() {
	if apiKey == SandboxKey {
		fmt.Println("Демо-ключ: ответы сгенерированы (моки), не реальные данные.")
		fmt.Println()
	}

	// Дефолт — Москва. Координаты передаются двумя аргументами: go run . 59.93 30.33
	latitude, longitude := 55.7558, 37.6173
	if len(os.Args) > 2 {
		var err error
		if latitude, err = strconv.ParseFloat(os.Args[1], 64); err != nil {
			fmt.Fprintln(os.Stderr, "Ошибка: широта должна быть числом:", err)
			os.Exit(1)
		}
		if longitude, err = strconv.ParseFloat(os.Args[2], 64); err != nil {
			fmt.Fprintln(os.Stderr, "Ошибка: долгота должна быть числом:", err)
			os.Exit(1)
		}
	}

	weather, err := GetWeather(latitude, longitude)
	if err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка:", err)
		os.Exit(1)
	}

	fmt.Printf("Погода по координатам %v, %v\n", latitude, longitude)
	fmt.Printf("  Точка:        %s (%s), coord из ответа: %v, %v\n",
		weather.Name, weather.Sys.Country, weather.Coord.Lat, weather.Coord.Lon)
	fmt.Printf("  Замер:        %s\n", LocalTime(weather.Dt, weather.Timezone))

	if len(weather.Weather) > 0 {
		fmt.Printf("  Небо:         %s (%s)\n",
			weather.Weather[0].Description, weather.Weather[0].Main)
	}

	fmt.Printf("  Температура:  %.1f °C, ощущается как %.1f °C\n",
		weather.Main.Temp, weather.Main.FeelsLike)

	gust := ""
	if weather.Wind.Gust != nil {
		gust = fmt.Sprintf(", порывы до %.1f м/с", *weather.Wind.Gust)
	}
	fmt.Printf("  Ветер:        %.1f м/с%s, направление %d° (%s)\n",
		weather.Wind.Speed, gust, weather.Wind.Deg, Compass(weather.Wind.Deg))

	fmt.Printf("  Влажность:    %d %%\n", weather.Main.Humidity)
	fmt.Printf("  Облачность:   %d %%\n", weather.Clouds.All)
	if weather.Visibility != nil {
		fmt.Printf("  Видимость:    %d м\n", *weather.Visibility)
	}
	fmt.Printf("  Давление:     %d гПа\n", weather.Main.Pressure)

	assessment := AssessConditions(weather)
	fmt.Printf("\nВЕРДИКТ: %s\n", assessment.Verdict)
	for _, reason := range assessment.Critical {
		fmt.Println("  [!]", reason)
	}
	for _, reason := range assessment.Warnings {
		fmt.Println("  [~]", reason)
	}
	if assessment.Verdict == Allow {
		fmt.Println("  Ограничений по погоде нет.")
	}

	if apiKey == SandboxKey {
		fmt.Println("\nПесочница: значения генерируются и могут противоречить друг другу и")
		fmt.Println("координатам (name не связан с coord, дождь при -14 °C — норма для мока).")
		fmt.Println("Проверять логику приложения на них можно, делать выводы о погоде — нет.")
	}
}
