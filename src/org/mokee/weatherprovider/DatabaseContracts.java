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

public class DatabaseContracts {

    protected static final String DB_NAME = "location.db";
    protected static final int SCHEMA = 1;

    public static final String AREAID = "AREAID";
    public static final String NAMEEN = "NAMEEN";
    public static final String NAMECN = "NAMECN";
    public static final String DISTRICTEN = "DISTRICTEN";
    public static final String DISTRICTCN = "DISTRICTCN";
    public static final String NATIONCN = "NATIONCN";

    public static final String[] PROJECTION = new String[] {
            AREAID,
            NAMEEN,
            NAMECN,
            DISTRICTEN,
            DISTRICTCN,
            NATIONCN
    };

    public static final int AREAID_INDEX = 0;
    public static final int NAMEEN_INDEX = 1;
    public static final int NAMECN_INDEX = 2;
    public static final int DISTRICTEN_INDEX = 3;
    public static final int DISTRICTCN_INDEX = 4;
    public static final int NATIONCN_INDEX = 5;
}
