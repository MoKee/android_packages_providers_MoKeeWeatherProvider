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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.location.Location;
import android.mokee.utils.MoKeeUtils;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.mokee.security.RSAUtils;

import net.sqlcipher.Cursor;
import net.sqlcipher.database.SQLiteDatabase;

import mokee.providers.WeatherContract;
import mokee.weather.MKWeatherManager;
import mokee.weather.RequestInfo;
import mokee.weather.WeatherInfo;
import mokee.weather.WeatherInfo.DayForecast;
import mokee.weather.WeatherLocation;
import mokee.weatherservice.ServiceRequest;
import mokee.weatherservice.ServiceRequestResult;
import mokee.weatherservice.WeatherProviderService;

public class MoKeeWeatherProviderService extends WeatherProviderService {

    private Context mContext;

    private static final String TAG = MoKeeWeatherProviderService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String MOKEE_API_MAIN_NODE = "MoKeeWeather data service 2.0";

    private static final String URL_PARAM_LATITUDE_LONGITUDE = "lat=%f&lon=%f";
    private static final String URL_PARAM_CITY_ID = "id=%s";

    private static final String URL_WEATHER =
            "http://cloud.mokeedev.com/weather/getWeatherByCityIDv2";

    private Map<ServiceRequest,WeatherUpdateRequestTask> mWeatherUpdateRequestMap = new HashMap<>();
    private Map<ServiceRequest,LookupCityNameRequestTask> mLookupCityRequestMap = new HashMap<>();

    //MoKeeWeather recommends to wait 10 min between requests
    private final static long REQUEST_THRESHOLD = 1000L * 60L * 10L;
    private long mLastRequestTimestamp = -REQUEST_THRESHOLD;
    public static WeatherLocation mLastWeatherLocation;
    public static Location mLastLocation;
    //5km of threshold, the weather won't change that much in such short distance
    private static final float LOCATION_DISTANCE_METERS_THRESHOLD = 5f * 1000f;

    @Override
    public void onCreate() {
        mContext = getApplicationContext();
    }

    @Override
    protected void onRequestSubmitted(ServiceRequest request) {
        RequestInfo requestInfo = request.getRequestInfo();
        int requestType = requestInfo.getRequestType();
        if (DEBUG) Log.d(TAG, "Received request type " + requestType);

        if (((requestType == RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ &&
                isSameGeoLocation(requestInfo.getLocation(), mLastLocation))
                    || (requestType == RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ &&
                        isSameWeatherLocation(requestInfo.getWeatherLocation(),
                                mLastWeatherLocation))) && requestSubmittedTooSoon()) {
            request.reject(MKWeatherManager.RequestStatus.SUBMITTED_TOO_SOON);
            return;
        }

        switch (requestType) {
            case RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ:
            case RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ:
                synchronized (mWeatherUpdateRequestMap) {
                    WeatherUpdateRequestTask weatherTask = new WeatherUpdateRequestTask(request);
                    mWeatherUpdateRequestMap.put(request, weatherTask);
                    mLastRequestTimestamp = SystemClock.elapsedRealtime();
                    weatherTask.execute();
                }
                break;
            case RequestInfo.TYPE_LOOKUP_CITY_NAME_REQ:
                synchronized (mLookupCityRequestMap) {
                    LookupCityNameRequestTask lookupTask = new LookupCityNameRequestTask(request);
                    mLookupCityRequestMap.put(request, lookupTask);
                    lookupTask.execute();
                }
                break;
        }
    }

    private boolean requestSubmittedTooSoon() {
        final long now = SystemClock.elapsedRealtime();
        if (DEBUG) Log.d(TAG, "Now " + now + " last request " + mLastRequestTimestamp);
        return (mLastRequestTimestamp + REQUEST_THRESHOLD > now);
    }
    
    private class WeatherUpdateRequestTask extends AsyncTask<Void, Void, WeatherInfo> {
        final ServiceRequest mRequest;
        public WeatherUpdateRequestTask(ServiceRequest request) {
            mRequest = request;
        }

        public WeatherInfo getWeatherInfo(Location location, boolean metric) {
            StringBuffer params = new StringBuffer();
            params.append("ak=").append(MoKeeWeatherApplication.API_KEY)
            .append("&callback=renderReverse&output=json&pois=1&")
            .append("location=").append(location.getLatitude()).append(",")
            .append(location.getLongitude());
            String locationResponse = HttpRetriever.retrieve(MoKeeWeatherApplication.URL_PLACEFINDER, params.toString());
            if (locationResponse != null) {
                try {
                    JSONObject address = new JSONObject(locationResponse).getJSONObject("result").getJSONObject("addressComponent");
                    String resultCityName = getFormattedName(address.getString("city"));
                    String resultDistrictName = getFormattedName(address.getString("district"));
                    String cityNameEn = "";
                    String areaID = "";
                    if (!resultCityName.isEmpty() && address.getInt("country_code") == 0) {
                        DatabaseHelper databaseHelper = new DatabaseHelper(mContext);
                        SQLiteDatabase sqLiteDatabase = databaseHelper.getReadableDatabase();
                        Cursor cursor = sqLiteDatabase.query("weathers", DatabaseContracts.PROJECTION,
                                "DISTRICTCN like '" + resultCityName + "'", null, null, null, null);
                        while (cursor.moveToNext()) {
                            String cityNameCn = cursor.getString(DatabaseContracts.NAMECN_INDEX);
                            if (TextUtils.isEmpty(areaID) || !TextUtils.isEmpty(resultDistrictName) && resultDistrictName.contains(cityNameCn)) {
                                areaID = cursor.getString(DatabaseContracts.AREAID_INDEX);
                                resultCityName = cityNameCn;
                                cityNameEn = cursor.getString(DatabaseContracts.NAMEEN_INDEX);
                            }
                        }
                        cursor.close();
                        sqLiteDatabase.close();
                        if (!TextUtils.isEmpty(areaID)) {
                            return getWeatherInfo(areaID, MoKeeUtils.isSupportLanguage(false) ? resultCityName : getFormattedNameLetter(cityNameEn), metric);
                        } else {
                            return null;
                        }
                    } else {
                        String selection = String.format(Locale.US, URL_PARAM_LATITUDE_LONGITUDE,
                                mRequest.getRequestInfo().getLocation().getLatitude(),
                                mRequest.getRequestInfo().getLocation().getLongitude());
                        return GlobalWeatherProvider.getWeatherInfo(mContext, mRequest, selection);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        public WeatherInfo getWeatherInfo(String id, String localizedCityName, boolean metric) {
            StringBuffer params = new StringBuffer();
            try {
                String cityID = RSAUtils.rsaEncryptByPublicKey(id);
                params.append("city_id=").append(cityID);
            } catch (Exception e) {
                e.printStackTrace();
            }
            String forecastResponse = HttpRetriever.retrieve(URL_WEATHER, params.toString());
            if (forecastResponse != null) {
                try {
                    JSONObject weather = new JSONObject(forecastResponse).getJSONArray(MOKEE_API_MAIN_NODE).getJSONObject(0);
                    JSONObject main = weather.getJSONObject("now");
                    ArrayList<DayForecast> forecasts = parseForecasts(weather.getJSONArray("daily_forecast"), true);
                    WeatherInfo.Builder weatherInfo = new WeatherInfo.Builder(localizedCityName,
                            GlobalWeatherProvider.sanitizeTemperature(main.getDouble("tmp"), metric), metric ? WeatherContract.WeatherColumns.TempUnit.CELSIUS :
                            WeatherContract.WeatherColumns.TempUnit.FAHRENHEIT);
                    weatherInfo.setWind(main.getJSONObject("wind").getDouble("spd"), main.getJSONObject("wind").getDouble("deg"), WeatherContract.WeatherColumns.WindSpeedUnit.KPH);
                    weatherInfo.setHumidity(main.getDouble("hum"));
                    weatherInfo.setTodaysLow(forecasts.get(0).getLow());
                    weatherInfo.setTodaysHigh(forecasts.get(0).getHigh());
                    weatherInfo.setTimestamp(System.currentTimeMillis());
                    weatherInfo.setWeatherCondition(mapConditionIconToCode(main.getJSONObject("cond").getInt("code")));
                    if (weather.has("aqi")) {
                        JSONObject aqiInfo = weather.getJSONObject("aqi").getJSONObject("city");
                        weatherInfo.setAqi(getAqiLevelName(aqiInfo.getInt("aqi")));
                    }
                    if (weather.has("suggestion")) {
                        JSONObject suggestion = weather.getJSONObject("suggestion");
                        if (suggestion.has("uv")) {
                            weatherInfo.setUv(getString(R.string.uv) + " " + suggestion.getJSONObject("uv").getString("brf"));
                        }
                    }
                    weatherInfo.setForecast(forecasts);

                    if (mRequest.getRequestInfo().getRequestType()
                            == RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ) {
                        mLastWeatherLocation = mRequest.getRequestInfo().getWeatherLocation();
                        mLastLocation = null;
                    } else if (mRequest.getRequestInfo().getRequestType()
                            == RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ) {
                        mLastLocation = mRequest.getRequestInfo().getLocation();
                        mLastWeatherLocation = null;
                    }
                    return weatherInfo.build();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        @Override
        protected WeatherInfo doInBackground(Void... params) {
            if (mRequest.getRequestInfo().getRequestType()
                    == RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ) {
                String CityId = mRequest.getRequestInfo().getWeatherLocation().getCityId();
                if (mRequest.getRequestInfo().getWeatherLocation().getCountryId().equals("0086")) {
                    return getWeatherInfo(CityId, mRequest.getRequestInfo().getWeatherLocation().getCity(), true);
                } else {
                    String selection = String.format(Locale.US, URL_PARAM_CITY_ID, CityId);
                    return GlobalWeatherProvider.getWeatherInfo(mContext, mRequest, selection);
                }
            } else if (mRequest.getRequestInfo().getRequestType()
                    == RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ) {
                return getWeatherInfo(mRequest.getRequestInfo().getLocation(), true);
            } else {
                return null;
            }
        }

        private ArrayList<DayForecast> parseForecasts(JSONArray forecasts, boolean metric)
                throws JSONException {
            ArrayList<DayForecast> result = new ArrayList<>();
            int count = forecasts.length();

            if (count == 0) {
                throw new JSONException("Empty forecasts array");
            }
            String firstDayTime = forecasts.getJSONObject(0).getString("date");
            if (isYesterday(firstDayTime)) {
                forecasts.remove(0);
            }
            for (int i = 0; i < GlobalWeatherProvider.FORECAST_DAYS; i++) {
                JSONObject forecast = forecasts.getJSONObject(i);
                int weatherID = forecast.getJSONObject("cond").getInt("code_d");
                DayForecast item = new DayForecast.Builder(mapConditionIconToCode(weatherID))
                        .setLow(GlobalWeatherProvider.sanitizeTemperature(forecast.getJSONObject("tmp").getDouble("min"), metric))
                        .setHigh(GlobalWeatherProvider.sanitizeTemperature(forecast.getJSONObject("tmp").getDouble("max"), metric)).build();
                result.add(item);
            }
            return result;
        }

        private int mapConditionIconToCode(int conditionId) {
            switch (conditionId) {
                case 100: //晴
                    return WeatherContract.WeatherColumns.WeatherCode.SUNNY;
                case 101: //多云
                    return WeatherContract.WeatherColumns.WeatherCode.CLOUDY;
                case 102: //少云
                    return WeatherContract.WeatherColumns.WeatherCode.FEW_CLOUDS;
                case 103: //晴间多云
                    return WeatherContract.WeatherColumns.WeatherCode.PARTLY_CLOUDY;
                case 104: //阴
                    return WeatherContract.WeatherColumns.WeatherCode.OVERCAST;
                case 200: //有风
                    return WeatherContract.WeatherColumns.WeatherCode.WINDY;
                case 201: //平静
                    return WeatherContract.WeatherColumns.WeatherCode.CALM;
                case 202: //微风
                    return WeatherContract.WeatherColumns.WeatherCode.LIGHT_BREEZE;
                case 203: //和风
                    return WeatherContract.WeatherColumns.WeatherCode.MODERATE_BREEZE;
                case 204: //清风
                    return WeatherContract.WeatherColumns.WeatherCode.FRESH_BREEZE;
                case 205: //强风/劲风
                    return WeatherContract.WeatherColumns.WeatherCode.STRONG_BREEZE;
                case 206: //疾风
                    return WeatherContract.WeatherColumns.WeatherCode.HIGH_WIND;
                case 207: //大风
                    return WeatherContract.WeatherColumns.WeatherCode.GALE;
                case 208: //烈风
                    return WeatherContract.WeatherColumns.WeatherCode.STRONG_GALE;
                case 209: //风暴
                    return WeatherContract.WeatherColumns.WeatherCode.STORM;
                case 210: //狂爆风
                    return WeatherContract.WeatherColumns.WeatherCode.VIOLENT_STORM;
                case 211: //飓风
                    return WeatherContract.WeatherColumns.WeatherCode.HURRICANE;
                case 212: //龙卷风
                    return WeatherContract.WeatherColumns.WeatherCode.TORNADO;
                case 213: //热带风暴
                    return WeatherContract.WeatherColumns.WeatherCode.TROPICAL_STORM;
                case 300: //阵雨
                    return WeatherContract.WeatherColumns.WeatherCode.SHOWER_RAIN;
                case 301: //强阵雨
                    return WeatherContract.WeatherColumns.WeatherCode.HEAVY_SHOWER_RAIN;
                case 302: //雷阵雨
                    return WeatherContract.WeatherColumns.WeatherCode.THUNDERSHOWER;
                case 303: //强雷阵雨
                    return WeatherContract.WeatherColumns.WeatherCode.HEAVY_THUNDERSTORM;
                case 304: //雷阵雨伴有冰雹
                    return WeatherContract.WeatherColumns.WeatherCode.HAIL;
                case 305: //小雨
                    return WeatherContract.WeatherColumns.WeatherCode.LIGHT_RAIN;
                case 306: //中雨
                    return WeatherContract.WeatherColumns.WeatherCode.MODERATE_RAIN;
                case 307: //大雨
                    return WeatherContract.WeatherColumns.WeatherCode.HEAVY_RAIN;
                case 308: //极端降雨
                    return WeatherContract.WeatherColumns.WeatherCode.EXTREME_RAIN;
                case 309: //毛毛雨/细雨
                    return WeatherContract.WeatherColumns.WeatherCode.DRIZZLE_RAIN;
                case 310: //暴雨
                    return WeatherContract.WeatherColumns.WeatherCode.RAIN_STORM;
                case 311: //大暴雨
                    return WeatherContract.WeatherColumns.WeatherCode.HEAVY_RAIN_STORM;
                case 312: //特大暴雨
                    return WeatherContract.WeatherColumns.WeatherCode.SEVERE_RAIN_STORM;
                case 313: //冻雨
                    return WeatherContract.WeatherColumns.WeatherCode.FREEZING_RAIN;
                case 400: //小雪
                    return WeatherContract.WeatherColumns.WeatherCode.LIGHT_SNOW;
                case 401: //中雪
                    return WeatherContract.WeatherColumns.WeatherCode.MODERATE_SNOW;
                case 402: //大雪
                    return WeatherContract.WeatherColumns.WeatherCode.HEAVY_SNOW;
                case 403: //暴雪
                    return WeatherContract.WeatherColumns.WeatherCode.SNOWSTORM;
                case 404: //雨夹雪
                    return WeatherContract.WeatherColumns.WeatherCode.SLEET;
                case 405: //雨雪天气
                    return WeatherContract.WeatherColumns.WeatherCode.RAIN_WITH_SNOW;
                case 406: //阵雨夹雪
                    return WeatherContract.WeatherColumns.WeatherCode.SHOWER_SNOW;
                case 407: //阵雪
                    return WeatherContract.WeatherColumns.WeatherCode.SNOW_FLURRY;
                case 500: //薄雾
                    return WeatherContract.WeatherColumns.WeatherCode.MIST;
                case 501: //雾
                    return WeatherContract.WeatherColumns.WeatherCode.FOGGY;
                case 502: //霾
                    return WeatherContract.WeatherColumns.WeatherCode.HAZE;
                case 503: //扬沙
                    return WeatherContract.WeatherColumns.WeatherCode.SAND;
                case 504: //浮尘
                    return WeatherContract.WeatherColumns.WeatherCode.DUST;
                case 506: //火山灰
                    return WeatherContract.WeatherColumns.WeatherCode.VOLCANIC_ASH;
                case 507: //沙尘暴
                    return WeatherContract.WeatherColumns.WeatherCode.DUSTSTORM;
                case 508: //强沙尘暴
                    return WeatherContract.WeatherColumns.WeatherCode.SANDSTORM;
                case 900: //热
                    return WeatherContract.WeatherColumns.WeatherCode.HOT;
                case 901: //冷
                    return WeatherContract.WeatherColumns.WeatherCode.COLD;
            }
            return WeatherContract.WeatherColumns.WeatherCode.NOT_AVAILABLE;
        }

        @Override
        protected void onPostExecute(WeatherInfo weatherInfo) {
            if (weatherInfo == null) {
                if (DEBUG) Log.d(TAG, "Received null weather info, failing request");
                mRequest.fail();
            } else {
                if (DEBUG) Log.d(TAG, weatherInfo.toString());
                ServiceRequestResult result = new ServiceRequestResult.Builder(weatherInfo).build();
                mRequest.complete(result);
            }
        }
    }

    private boolean isSameWeatherLocation(WeatherLocation newLocation,
            WeatherLocation oldLocation) {
        if (newLocation == null || oldLocation == null) return false;
        return (newLocation.getCityId().equals(oldLocation.getCityId())
                && newLocation.getCity().equals(oldLocation.getCity())
                && newLocation.getPostalCode().equals(oldLocation.getPostalCode())
                && newLocation.getCountry().equals(oldLocation.getCountry())
                && newLocation.getCountryId().equals(oldLocation.getCountryId()));
    }

    private boolean isSameGeoLocation(Location newLocation, Location oldLocation) {
        if (newLocation == null || oldLocation == null) return false;
        float distance = newLocation.distanceTo(oldLocation);
        if (DEBUG) Log.d(TAG, "Distance between locations " + distance);
        return (distance < LOCATION_DISTANCE_METERS_THRESHOLD);
    }

    private class LookupCityNameRequestTask
            extends AsyncTask<Void, Void, ArrayList<WeatherLocation>> {

        final ServiceRequest mRequest;
        public LookupCityNameRequestTask(ServiceRequest request) {
            mRequest = request;
        }

        @Override
        protected ArrayList<WeatherLocation> doInBackground(Void... params) {
            ArrayList<WeatherLocation> locations = getLocations(
                    mRequest.getRequestInfo().getCityName());
            return locations;
        }

        @Override
        protected void onPostExecute(ArrayList<WeatherLocation> locations) {
            if (locations != null) {
                if (DEBUG) {
                    for (WeatherLocation location : locations) {
                        Log.d(TAG, location.toString());
                    }
                }
                ServiceRequestResult request = new ServiceRequestResult.Builder(locations).build();
                mRequest.complete(request);
            } else {
                mRequest.fail();
            }
        }

        private ArrayList<WeatherLocation> getLocations(String input) {
            String searchText = getFormattedName(input.toLowerCase());
            ArrayList<WeatherLocation> results = new ArrayList<>();

            DatabaseHelper databaseHelper = new DatabaseHelper(mContext);
            SQLiteDatabase sqLiteDatabase = databaseHelper.getReadableDatabase();
            Cursor cursor = sqLiteDatabase.query("weathers", DatabaseContracts.PROJECTION, null, null, null, null, null);

            while (cursor.moveToNext()) {
                String areaID = cursor.getString(DatabaseContracts.AREAID_INDEX);
                String nameCN = cursor.getString(DatabaseContracts.NAMECN_INDEX);
                String nameEN = cursor.getString(DatabaseContracts.NAMEEN_INDEX);
                String districtEN = cursor.getString(DatabaseContracts.DISTRICTEN_INDEX);
                String districtCN = cursor.getString(DatabaseContracts.DISTRICTCN_INDEX);
                String nationCN = cursor.getString(DatabaseContracts.NATIONCN_INDEX);
                String countryID = "0086";

                if (searchText.equals(districtEN) || searchText.equals(nameEN) || searchText.contains(districtCN) || searchText.contains(nameCN)) {
                    WeatherLocation weatherLocation = new WeatherLocation.Builder(areaID, MoKeeUtils.isSupportLanguage(false) ? nameCN : getFormattedNameLetter(nameEN))
                            .setCountry(nationCN).setCountryId(countryID).build();
                    results.add(weatherLocation);
                }
            }
            cursor.close();
            sqLiteDatabase.close();
            if (results.size() == 0) {
                return GlobalWeatherProvider.getLocations(mContext, input);
            }
            return results;
        }
    }

    @Override
    protected void onRequestCancelled(ServiceRequest request) {
        switch (request.getRequestInfo().getRequestType()) {
            case RequestInfo.TYPE_WEATHER_BY_WEATHER_LOCATION_REQ:
            case RequestInfo.TYPE_WEATHER_BY_GEO_LOCATION_REQ:
                synchronized (mWeatherUpdateRequestMap) {
                    WeatherUpdateRequestTask task = mWeatherUpdateRequestMap.remove(request);
                    if (task != null) {
                        task.cancel(true);
                    }
                    return;
                }
            case RequestInfo.TYPE_LOOKUP_CITY_NAME_REQ:
                synchronized (mLookupCityRequestMap) {
                    LookupCityNameRequestTask task = mLookupCityRequestMap.remove(request);
                    if (task != null) {
                        task.cancel(true);
                    }
                }
                return;
            default:
                if (DEBUG) Log.w(TAG, "Received unknown request type "
                        + request.getRequestInfo().getRequestType());
                break;
        }
    }

    private boolean isYesterday (String firstDayTime) throws JSONException {
        Date date = new Date(new Date().getTime() - 24 * 60 * 60 * 1000);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        String yesterDayTime = format.format(date);
        return (firstDayTime.equals(yesterDayTime));
    }

    private String getAqiLevelName(int aqi) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(getString(R.string.aqi)).append(" ").append(aqi).append(" ");
        if (aqi <= 50) {
            stringBuilder.append(getString(R.string.aqi_level_1));
        } else if (aqi >= 51 && aqi <= 100) {
            stringBuilder.append(getString(R.string.aqi_level_2));
        } else if (aqi >= 101 && aqi <= 150) {
            stringBuilder.append(getString(R.string.aqi_level_3));
        } else if (aqi >= 151 && aqi <= 200) {
            stringBuilder.append(getString(R.string.aqi_level_4));
        } else if (aqi >= 201 && aqi <= 300) {
            stringBuilder.append(getString(R.string.aqi_level_5));
        } else {
            stringBuilder.append(getString(R.string.aqi_level_6));
        }
        return stringBuilder.toString();
    }

    private String getFormattedName(String cityName) {
        if (cityName.length() > 2 && cityName.endsWith("市")) {
            return cityName.replace("市", "");
        } else if (cityName.length() > 2 && cityName.endsWith("县")) {
            return cityName.replace("县", "");
        } else {
            return cityName;
        }
    }

    private String getFormattedNameLetter(String cityName) {
        return cityName.replaceFirst(cityName.substring(0, 1), cityName.substring(0, 1).toUpperCase());
    }
}