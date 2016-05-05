/*
 *  Copyright (C) 2016 The MoKee Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mokee.weatherprovider;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import mokee.providers.WeatherContract;
import mokee.weather.RequestInfo;
import mokee.weather.WeatherInfo;
import mokee.weather.WeatherInfo.DayForecast;
import mokee.weather.WeatherLocation;
import mokee.weatherservice.ServiceRequest;

public class GlobalWeatherProvider {

    private static final String mAPIKey = "9f65b19b7a6648346dda93c6973a682c";

    private static final String TAG = GlobalWeatherProvider.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int FORECAST_DAYS = 4;

    private static final String URL_LOCATION =
            "http://api.openweathermap.org/data/2.5/find?q=%s&mode=json&lang=%s&appid=%s";
    private static final String URL_WEATHER =
            "http://api.openweathermap.org/data/2.5/weather?%s&mode=json&units=%s&lang=%s&appid=%s";
    private static final String URL_FORECAST =
            "http://api.openweathermap.org/data/2.5/forecast/daily?" +
                    "%s&mode=json&units=%s&lang=%s&cnt=" + FORECAST_DAYS + "&appid=%s";

    public static WeatherInfo getWeatherInfo(Context context, ServiceRequest mRequest, String selection) {
        String locale = getLanguageCode(context);

        //TODO Read units from settings
        String currentConditionURL = String.format(Locale.US, URL_WEATHER, selection, "metric",
                locale, mAPIKey);
        if (DEBUG) Log.d(TAG, "Current condition URL " + currentConditionURL);
        String currentConditionResponse = HttpRetriever.retrieve(currentConditionURL);
        if (currentConditionResponse == null) return null;
        if (DEBUG) Log.d(TAG, "Response " + currentConditionResponse);

        String forecastUrl = String.format(Locale.US, URL_FORECAST, selection, "metric",
                locale, mAPIKey);
        if (DEBUG) Log.d(TAG, "Forecast URL " + forecastUrl);
        String forecastResponse = HttpRetriever.retrieve(forecastUrl);
        if (forecastUrl == null) return null;
        if (DEBUG) Log.d(TAG, "Response " + forecastResponse);

        try {
            JSONObject currentCondition = new JSONObject(currentConditionResponse);
            if (currentCondition.has("cod")) {
                if (TextUtils.equals("404", currentCondition.getString("cod"))) {
                    //OpenWeatherMap might return 404 even if we supply a valid location or the
                    //data that we got by looking up a city...not our fault
                    return null;
                }
            }
            JSONObject weather = currentCondition.getJSONArray("weather").getJSONObject(0);
            JSONObject main = currentCondition.getJSONObject("main");
            JSONObject wind = currentCondition.getJSONObject("wind");
            ArrayList<DayForecast> forecasts =
                    parseForecasts(new JSONObject(forecastResponse).getJSONArray("list"), true);

            String cityName = null;
            if (mRequest.getRequestInfo().getRequestType()
                    == RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ) {
                cityName = mRequest.getRequestInfo().getWeatherLocation().getCity();
            }
            if (cityName == null || TextUtils.equals(cityName, "")) {
                cityName = currentCondition.getString("name");
                if (cityName == null) return null;
            }

            WeatherInfo.Builder weatherInfo = new WeatherInfo.Builder(
                    cityName, sanitizeTemperature(main.getDouble("temp"), true),
                    WeatherContract.WeatherColumns.TempUnit.CELSIUS);
            weatherInfo.setHumidity(main.getDouble("humidity"));
            weatherInfo.setWind(wind.getDouble("speed"), wind.getDouble("deg"),
                    WeatherContract.WeatherColumns.WindSpeedUnit.KPH);
            weatherInfo.setTodaysLow(sanitizeTemperature(main.getDouble("temp_min"), true));
            weatherInfo.setTodaysHigh(sanitizeTemperature(main.getDouble("temp_max"), true));
            //NOTE: The timestamp provided by OpenWeatherMap corresponds to the time the data
            //was last updated by the stations. Let's use System.currentTimeMillis instead
            weatherInfo.setTimestamp(System.currentTimeMillis());
            weatherInfo.setWeatherCondition(mapConditionIconToCode(weather.getString("icon"),
                    weather.getInt("id")));
            weatherInfo.setForecast(forecasts);

            if (mRequest.getRequestInfo().getRequestType()
                    == RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ) {
                MoKeeWeatherProviderService.mLastWeatherLocation = mRequest.getRequestInfo().getWeatherLocation();
                MoKeeWeatherProviderService.mLastLocation = null;
            } else if (mRequest.getRequestInfo().getRequestType()
                    == RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ) {
                MoKeeWeatherProviderService.mLastLocation = mRequest.getRequestInfo().getLocation();
                MoKeeWeatherProviderService.mLastWeatherLocation = null;
            }

            return weatherInfo.build();
        } catch (JSONException e) {
            //Received malformed or missing data
            if (DEBUG) Log.w(TAG, "JSONException while processing weather update", e);
        }
        return null;
    }

    private static ArrayList<DayForecast> parseForecasts(JSONArray forecasts, boolean metric)
            throws JSONException {
        ArrayList<DayForecast> result = new ArrayList<>();
        int count = forecasts.length();

        if (count == 0) {
            throw new JSONException("Empty forecasts array");
        }
        for (int i = 0; i < count; i++) {
            JSONObject forecast = forecasts.getJSONObject(i);
            JSONObject temperature = forecast.getJSONObject("temp");
            JSONObject weather = forecast.getJSONArray("weather").getJSONObject(0);
            DayForecast item = new WeatherInfo.DayForecast.Builder(mapConditionIconToCode(
                    weather.getString("icon"), weather.getInt("id")))
                    .setLow(sanitizeTemperature(temperature.getDouble("min"), metric))
                    .setHigh(sanitizeTemperature(temperature.getDouble("max"), metric)).build();
            result.add(item);
        }
        return result;
    }

    private static int mapConditionIconToCode(String icon, int conditionId) {

        // First, use condition ID for specific cases
        switch (conditionId) {
            // Thunderstorms
            case 202:   // thunderstorm with heavy rain
            case 232:   // thunderstorm with heavy drizzle
            case 211:   // thunderstorm
                return WeatherContract.WeatherColumns.WeatherCode.THUNDERSTORMS;
            case 212:   // heavy thunderstorm
                return WeatherContract.WeatherColumns.WeatherCode.HURRICANE;
            case 221:   // ragged thunderstorm
            case 231:   // thunderstorm with drizzle
            case 201:   // thunderstorm with rain
                return WeatherContract.WeatherColumns.WeatherCode.SCATTERED_THUNDERSTORMS;
            case 230:   // thunderstorm with light drizzle
            case 200:   // thunderstorm with light rain
            case 210:   // light thunderstorm
                return WeatherContract.WeatherColumns.WeatherCode.ISOLATED_THUNDERSTORMS;

            // Drizzle
            case 300:   // light intensity drizzle
            case 301:   // drizzle
            case 302:   // heavy intensity drizzle
            case 310:   // light intensity drizzle rain
            case 311:   // drizzle rain
            case 312:   // heavy intensity drizzle rain
            case 313:   // shower rain and drizzle
            case 314:   // heavy shower rain and drizzle
            case 321:   // shower drizzle
                return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE;

            // Rain
            case 500:   // light rain
            case 501:   // moderate rain
            case 520:   // light intensity shower rain
            case 521:   // shower rain
            case 531:   // ragged shower rain
            case 502:   // heavy intensity rain
            case 503:   // very heavy rain
            case 504:   // extreme rain
            case 522:   // heavy intensity shower rain
                return WeatherContract.WeatherColumns.WeatherCode.SHOWERS;
            case 511:   // freezing rain
                return WeatherContract.WeatherColumns.WeatherCode.FREEZING_RAIN;

            // Snow
            case 600: case 620: // light snow
                return WeatherContract.WeatherColumns.WeatherCode.LIGHT_SNOW_SHOWERS;
            case 601: case 621: // snow
                return WeatherContract.WeatherColumns.WeatherCode.SNOW;
            case 602: case 622: // heavy snow
                return WeatherContract.WeatherColumns.WeatherCode.HEAVY_SNOW;
            case 611: case 612: // sleet
                return WeatherContract.WeatherColumns.WeatherCode.SLEET;
            case 615: case 616: // rain and snow
                return WeatherContract.WeatherColumns.WeatherCode.MIXED_RAIN_AND_SNOW;

            // Atmosphere
            case 741:   // fog
                return WeatherContract.WeatherColumns.WeatherCode.FOGGY;
            case 711:   // smoke
            case 762:   // volcanic ash
                return WeatherContract.WeatherColumns.WeatherCode.SMOKY;
            case 701:   // mist
            case 721:   // haze
                return WeatherContract.WeatherColumns.WeatherCode.HAZE;
            case 731:   // sand/dust whirls
            case 751:   // sand
            case 761:   // dust
                return WeatherContract.WeatherColumns.WeatherCode.DUST;
            case 771:   // squalls
                return WeatherContract.WeatherColumns.WeatherCode.BLUSTERY;
            case 781:   // tornado
                return WeatherContract.WeatherColumns.WeatherCode.TORNADO;

            // Extreme
            case 900:   // tornado
                return WeatherContract.WeatherColumns.WeatherCode.TORNADO;
            case 901:   // tropical storm
                return WeatherContract.WeatherColumns.WeatherCode.TROPICAL_STORM;
            case 902:   // hurricane
                return WeatherContract.WeatherColumns.WeatherCode.HURRICANE;
            case 903:   // cold
                return WeatherContract.WeatherColumns.WeatherCode.COLD;
            case 904:   // hot
                return WeatherContract.WeatherColumns.WeatherCode.HOT;
            case 905:   // windy
                return WeatherContract.WeatherColumns.WeatherCode.WINDY;
            case 906:   // hail
                return WeatherContract.WeatherColumns.WeatherCode.HAIL;
        }

        // Not yet handled - Use generic icon mapping
        Integer condition = ICON_MAPPING.get(icon);
        if (condition != null) {
            return condition;
        }

        return WeatherContract.WeatherColumns.WeatherCode.NOT_AVAILABLE;
    }

    public static ArrayList<WeatherLocation> getLocations(Context context, String input) {
        String url = String.format(URL_LOCATION, Uri.encode(input), getLanguageCode(context), mAPIKey);
        String response = HttpRetriever.retrieve(url);
        if (response == null) {
            return null;
        }
        try {
            JSONArray jsonResults = new JSONObject(response).getJSONArray("list");
            ArrayList<WeatherLocation> results = new ArrayList<>();
            int count = jsonResults.length();

            for (int i = 0; i < count; i++) {
                JSONObject result = jsonResults.getJSONObject(i);
                String cityId = result.getString("id");
                String cityName = result.getString("name");
                String country = result.getJSONObject("sys").getString("country");

                WeatherLocation weatherLocation = new WeatherLocation.Builder(cityId, cityName)
                        .setCountry(country).build();
                results.add(weatherLocation);
            }
            return results;
        } catch (JSONException e) {
            if (DEBUG) Log.w(TAG, "JSONException while processing location lookup", e);
        }
        return null;
    }

    private static final HashMap<String, Integer> ICON_MAPPING = new HashMap<>();
    static {
        ICON_MAPPING.put("01d", WeatherContract.WeatherColumns.WeatherCode.SUNNY);
        ICON_MAPPING.put("01n", WeatherContract.WeatherColumns.WeatherCode.CLEAR_NIGHT);
        ICON_MAPPING.put("02d", WeatherContract.WeatherColumns.WeatherCode.PARTLY_CLOUDY_DAY);
        ICON_MAPPING.put("02n", WeatherContract.WeatherColumns.WeatherCode.PARTLY_CLOUDY_NIGHT);
        ICON_MAPPING.put("03d", WeatherContract.WeatherColumns.WeatherCode.CLOUDY);
        ICON_MAPPING.put("03n", WeatherContract.WeatherColumns.WeatherCode.CLOUDY);
        ICON_MAPPING.put("04d", WeatherContract.WeatherColumns.WeatherCode.MOSTLY_CLOUDY_DAY);
        ICON_MAPPING.put("04n", WeatherContract.WeatherColumns.WeatherCode.MOSTLY_CLOUDY_NIGHT);
        ICON_MAPPING.put("09d", WeatherContract.WeatherColumns.WeatherCode.SHOWERS);
        ICON_MAPPING.put("09n", WeatherContract.WeatherColumns.WeatherCode.SHOWERS);
        ICON_MAPPING.put("10d", WeatherContract.WeatherColumns.WeatherCode.SCATTERED_SHOWERS);
        ICON_MAPPING.put("10n", WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER);
        ICON_MAPPING.put("11d", WeatherContract.WeatherColumns.WeatherCode.THUNDERSTORMS);
        ICON_MAPPING.put("11n", WeatherContract.WeatherColumns.WeatherCode.THUNDERSTORMS);
        ICON_MAPPING.put("13d", WeatherContract.WeatherColumns.WeatherCode.SNOW);
        ICON_MAPPING.put("13n", WeatherContract.WeatherColumns.WeatherCode.SNOW);
        ICON_MAPPING.put("50d", WeatherContract.WeatherColumns.WeatherCode.HAZE);
        ICON_MAPPING.put("50n", WeatherContract.WeatherColumns.WeatherCode.FOGGY);
    }

    private static final HashMap<String, String> LANGUAGE_CODE_MAPPING = new HashMap<>();
    static {
        LANGUAGE_CODE_MAPPING.put("bg-", "bg");
        LANGUAGE_CODE_MAPPING.put("de-", "de");
        LANGUAGE_CODE_MAPPING.put("es-", "sp");
        LANGUAGE_CODE_MAPPING.put("fi-", "fi");
        LANGUAGE_CODE_MAPPING.put("fr-", "fr");
        LANGUAGE_CODE_MAPPING.put("it-", "it");
        LANGUAGE_CODE_MAPPING.put("nl-", "nl");
        LANGUAGE_CODE_MAPPING.put("pl-", "pl");
        LANGUAGE_CODE_MAPPING.put("pt-", "pt");
        LANGUAGE_CODE_MAPPING.put("ro-", "ro");
        LANGUAGE_CODE_MAPPING.put("ru-", "ru");
        LANGUAGE_CODE_MAPPING.put("se-", "se");
        LANGUAGE_CODE_MAPPING.put("tr-", "tr");
        LANGUAGE_CODE_MAPPING.put("uk-", "ua");
        LANGUAGE_CODE_MAPPING.put("zh-CN", "zh_cn");
        LANGUAGE_CODE_MAPPING.put("zh-TW", "zh_tw");
    }

    private static String getLanguageCode(Context context) {
        Locale locale = context.getResources().getConfiguration().locale;
        String selector = locale.getLanguage() + "-" + locale.getCountry();
        for (Map.Entry<String, String> entry : LANGUAGE_CODE_MAPPING.entrySet()) {
            if (selector.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "en";
    }

    // MoKeeWeather sometimes returns temperatures in Kelvin even if we ask it
    // for deg C or deg F. Detect this and convert accordingly.
    public static double sanitizeTemperature(double value, boolean metric) {
        // threshold chosen to work for both C and F. 170 deg F is hotter
        // than the hottest place on earth.
        if (value > 170d) {
            // K -> deg C
            value -= 273.15d;
            if (!metric) {
                // deg C -> deg F
                value = (value * 1.8d) + 32d;
            }
        }
        return value;
    }

}
