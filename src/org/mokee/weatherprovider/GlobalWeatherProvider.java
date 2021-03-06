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

    public static final int FORECAST_DAYS = 5;

    private static final String URL_LOCATION =
            "http://api.openweathermap.org/data/2.5/find?q=%s&mode=json&lang=%s&appid=%s";
    private static final String URL_WEATHER =
            "http://api.openweathermap.org/data/2.5/weather?%s&mode=json&units=%s&lang=%s&appid=%s";
    private static final String URL_FORECAST =
            "http://api.openweathermap.org/data/2.5/forecast/daily?" +
                    "%s&mode=json&units=%s&lang=%s&cnt=" + FORECAST_DAYS + "&appid=%s";

    private static final String URL_UV_INDEX = "http://api.owm.io/air/1.0/uvi/current?lat=%s&lon=%s&%s";

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
            weatherInfo.setWeatherCondition(mapConditionIconToCode(weather.getInt("id")));
            weatherInfo.setForecast(forecasts);

            if (mRequest.getRequestInfo().getRequestType()
                    == RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ) {
                MoKeeWeatherProviderService.mLastWeatherLocation = mRequest.getRequestInfo().getWeatherLocation();
                MoKeeWeatherProviderService.mLastLocation = null;
            } else if (mRequest.getRequestInfo().getRequestType()
                    == RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ) {
                MoKeeWeatherProviderService.mLastLocation = mRequest.getRequestInfo().getLocation();
                if (MoKeeWeatherProviderService.mLastLocation != null) {
                    String uvIndexURL = String.format(Locale.US, URL_UV_INDEX, MoKeeWeatherProviderService.mLastLocation.getLatitude(),
                            MoKeeWeatherProviderService.mLastLocation.getLongitude(), mAPIKey);
                    String currentUVResponse = HttpRetriever.retrieve(uvIndexURL);
                    if (!TextUtils.isEmpty(currentUVResponse)) {
                        JSONObject uvIndex = new JSONObject(currentUVResponse);
                        if (uvIndex.has("value")) {
                            weatherInfo.setUv(getUVLevelName(context, uvIndex.getDouble("value")));
                        }
                    }
                }
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
            DayForecast item = new WeatherInfo.DayForecast.Builder(mapConditionIconToCode(weather.getInt("id")))
                    .setLow(sanitizeTemperature(temperature.getDouble("min"), metric))
                    .setHigh(sanitizeTemperature(temperature.getDouble("max"), metric)).build();
            result.add(item);
        }
        return result;
    }

    private static int mapConditionIconToCode(int conditionId) {
        switch (conditionId) {
            // Thunderstorms
            case 200: // thunderstorm with light rain
                return WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER;
            case 201: // thunderstorm with rain
                return WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER;
            case 202: // thunderstorm with heavy rain
                return WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER;
            case 210: // light thunderstorm
                return WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER;
            case 211: // thunderstorm
                return WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER;
            case 212: // heavy thunderstorm
                return WeatherContract.WeatherColumns.WeatherCode.HEAVY_THUNDERSTORM;
            case 221: // ragged thunderstorm
                return WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER;
            case 230: // thunderstorm with light drizzle
                return WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER;
            case 231: // thunderstorm with drizzle
                return WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER;
            case 232: // thunderstorm with heavy drizzle
                return WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER;

            // Drizzle
            case 300: // light intensity drizzle
                return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE_RAIN;
            case 301: // drizzle
                return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE_RAIN;
            case 302: // heavy intensity drizzle
                return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE_RAIN;
            case 310: // light intensity drizzle rain
                return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE_RAIN;
            case 311: // drizzle rain
                return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE_RAIN;
            case 312: // heavy intensity drizzle rain
                return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE_RAIN;
            case 313: // shower rain and drizzle
                return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE_RAIN;
            case 314: // heavy shower rain and drizzle
                return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE_RAIN;
            case 321: // shower drizzle
                return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE_RAIN;

            // Rain
            case 500: // light rain
                return WeatherContract.WeatherColumns.WeatherCode.LIGHT_RAIN;
            case 501: // moderate rain
                return WeatherContract.WeatherColumns.WeatherCode.MODERATE_RAIN;
            case 502: // heavy intensity rain
                return WeatherContract.WeatherColumns.WeatherCode.HEAVY_RAIN;
            case 503: // very heavy rain
                return WeatherContract.WeatherColumns.WeatherCode.HEAVY_RAIN;
            case 504: // extreme rain
                return WeatherContract.WeatherColumns.WeatherCode.EXTREME_RAIN;
            case 511: // freezing rain
                return WeatherContract.WeatherColumns.WeatherCode.FREEZING_RAIN;
            case 520: // light intensity shower rain
                return WeatherContract.WeatherColumns.WeatherCode.SHOWER_RAIN;
            case 521: // shower rain
                return WeatherContract.WeatherColumns.WeatherCode.SHOWER_RAIN;
            case 522: // heavy intensity shower rain
                return WeatherContract.WeatherColumns.WeatherCode.HEAVY_SHOWER_RAIN;
            case 531: // ragged shower rain
                return WeatherContract.WeatherColumns.WeatherCode.SHOWER_RAIN;

            // Snow
            case 600: // light snow
                return WeatherContract.WeatherColumns.WeatherCode.LIGHT_SNOW;
            case 601: // snow
                return WeatherContract.WeatherColumns.WeatherCode.MODERATE_SNOW;
            case 602: // heavy snow
                return WeatherContract.WeatherColumns.WeatherCode.HEAVY_SNOW;
            case 611: // sleet
                return WeatherContract.WeatherColumns.WeatherCode.SLEET;
            case 612: // shower sleet
                return WeatherContract.WeatherColumns.WeatherCode.RAIN_WITH_SNOW;
            case 615: // light rain and snow
                return WeatherContract.WeatherColumns.WeatherCode.RAIN_WITH_SNOW;
            case 616: // rain and snow
                return WeatherContract.WeatherColumns.WeatherCode.RAIN_WITH_SNOW;
            case 620: // light shower snow
                return WeatherContract.WeatherColumns.WeatherCode.SHOWER_SNOW;
            case 621: // shower snow
                return WeatherContract.WeatherColumns.WeatherCode.SHOWER_SNOW;
            case 622: // heavy shower snow
                return WeatherContract.WeatherColumns.WeatherCode.SHOWER_SNOW;

            // Atmosphere
            case 701: // mist
                return WeatherContract.WeatherColumns.WeatherCode.MIST;
            case 711: // smoke
                return WeatherContract.WeatherColumns.WeatherCode.FOGGY;
            case 721: // haze
                return WeatherContract.WeatherColumns.WeatherCode.HAZE;
            case 731: // sand, dust whirls
                return WeatherContract.WeatherColumns.WeatherCode.SAND;
            case 741: // fog
                return WeatherContract.WeatherColumns.WeatherCode.FOGGY;
            case 751: // sand
                return WeatherContract.WeatherColumns.WeatherCode.SAND;
            case 761: // dust
                return WeatherContract.WeatherColumns.WeatherCode.DUST;
            case 762: // volcanic ash
                return WeatherContract.WeatherColumns.WeatherCode.VOLCANIC_ASH;
            case 771: // squalls
                return WeatherContract.WeatherColumns.WeatherCode.HIGH_WIND;
            case 781: // tornado
                return WeatherContract.WeatherColumns.WeatherCode.TORNADO;

            // Clear
            case 800: // clear sky
                return WeatherContract.WeatherColumns.WeatherCode.SUNNY;

            // Clouds
            case 801: // few clouds
                return WeatherContract.WeatherColumns.WeatherCode.FEW_CLOUDS;
            case 802: // scattered clouds
                return WeatherContract.WeatherColumns.WeatherCode.FEW_CLOUDS;
            case 803: // broken clouds
                return WeatherContract.WeatherColumns.WeatherCode.FEW_CLOUDS;
            case 804: // overcast clouds
                return WeatherContract.WeatherColumns.WeatherCode.OVERCAST;

            // Extreme
            case 900: // tornado
                return WeatherContract.WeatherColumns.WeatherCode.TORNADO;
            case 901: // tropical storm
                return WeatherContract.WeatherColumns.WeatherCode.TROPICAL_STORM;
            case 902: // hurricane
                return WeatherContract.WeatherColumns.WeatherCode.HURRICANE;
            case 903: // cold
                return WeatherContract.WeatherColumns.WeatherCode.COLD;
            case 904: // hot
                return WeatherContract.WeatherColumns.WeatherCode.HOT;
            case 905: // windy
                return WeatherContract.WeatherColumns.WeatherCode.WINDY;
            case 906: // hail
                return WeatherContract.WeatherColumns.WeatherCode.HAIL;

            // Additional
            case 951: // calm
                return WeatherContract.WeatherColumns.WeatherCode.CALM;
            case 952: // light breeze
                return WeatherContract.WeatherColumns.WeatherCode.LIGHT_BREEZE;
            case 953: // gentle breeze
                return WeatherContract.WeatherColumns.WeatherCode.MODERATE_BREEZE;
            case 954: // moderate breeze
                return WeatherContract.WeatherColumns.WeatherCode.MODERATE_BREEZE;
            case 955: // fresh breeze
                return WeatherContract.WeatherColumns.WeatherCode.FRESH_BREEZE;
            case 956: // strong breeze
                return WeatherContract.WeatherColumns.WeatherCode.STRONG_BREEZE;
            case 957: // high wind, near gale
                return WeatherContract.WeatherColumns.WeatherCode.HIGH_WIND;
            case 958: // gale
                return WeatherContract.WeatherColumns.WeatherCode.GALE;
            case 959: // severe gale
                return WeatherContract.WeatherColumns.WeatherCode.STRONG_GALE;
            case 960: // storm
                return WeatherContract.WeatherColumns.WeatherCode.STORM;
            case 961: // violent storm
                return WeatherContract.WeatherColumns.WeatherCode.VIOLENT_STORM;
            case 962: // hurricane
                return WeatherContract.WeatherColumns.WeatherCode.HURRICANE;
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

    private static String getUVLevelName(Context context, double index) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(context.getString(R.string.uv)).append(" ");
        if (index < 3) {
            stringBuilder.append(context.getString(R.string.uv_level_1));
        } else if (index >= 3 && index < 6) {
            stringBuilder.append(context.getString(R.string.uv_level_2));
        } else if (index >= 6 && index < 8) {
            stringBuilder.append(context.getString(R.string.uv_level_3));
        } else if (index >= 8 && index < 11) {
            stringBuilder.append(context.getString(R.string.uv_level_4));
        } else {
            stringBuilder.append(context.getString(R.string.uv_level_5));
        }
        return stringBuilder.toString();
    }

}
