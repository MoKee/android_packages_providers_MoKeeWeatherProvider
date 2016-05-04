/*
 * Copyright (C) 2016 The MoKee Open Source Project
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

import android.app.Application;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MoKeeWeatherApplication extends Application {


    private static final String LIB_NAME = "security";

    static {
        System.loadLibrary(LIB_NAME);
    }

    protected static String DB_PASSWORD = getDBPassword();
    protected static String API_KEY = getApiKey();
    protected static final String URL_PLACEFINDER = getPlaceFinderURL();

    @Override
    public void onCreate() {
        super.onCreate();
        File databaseFile = getDatabasePath(DatabaseContracts.DB_NAME);
        if (databaseFile.exists()) {
            return;
        } else {
            databaseFile.getParentFile().mkdirs();
            try {
                InputStream inputStream = getAssets().open(DatabaseContracts.DB_NAME);
                FileOutputStream fileOutputStream = new FileOutputStream(databaseFile.getAbsolutePath());
                byte[] buffer = new byte[512];
                int count = 0;
                while ((count = inputStream.read(buffer)) > 0) {
                    fileOutputStream.write(buffer, 0 , count);
                }
                fileOutputStream.flush();
                fileOutputStream.close();
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static native String nativeGetDBPassword();

    private static synchronized String getDBPassword() {
        return nativeGetDBPassword();
    }

    private static native String nativeGetPlaceFinderURL();

    private static synchronized String getPlaceFinderURL() {
        return nativeGetPlaceFinderURL();
    }

    private static native String nativeGetApiKey();

    private static synchronized String getApiKey() {
        return nativeGetApiKey();
    }

}
