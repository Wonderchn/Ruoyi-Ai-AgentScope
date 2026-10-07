/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.ingest;

import com.nageoffer.ai.ragent.runtime.RunApiException;
import com.nageoffer.ai.ragent.runtime.RunErrorCode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Fixed provider endpoints, bounded transport, no redirects or automatic retry. */
final class ProviderHttp {
    private ProviderHttp() {}
    static HttpURLConnection open(URI endpoint,String key,String payload) throws IOException {
        if(key==null || key.isBlank()) throw new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,"provider key is not configured");
        var connection=(HttpURLConnection)endpoint.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization","Bearer "+key);
        connection.setRequestProperty("Content-Type","application/json");
        connection.setDoOutput(true);
        byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try(var output=connection.getOutputStream()) {output.write(bytes);}
        return connection;
    }
    static String line(Reader reader,int maximum) throws IOException {
        StringBuilder value=new StringBuilder();
        for(int c;(c=reader.read())!=-1;) {
            if(c=='\n') return value.toString();
            if(value.length()>=maximum) throw new IOException("provider frame exceeds limit");
            if(c!='\r') value.append((char)c);
        }
        return value.isEmpty()?null:value.toString();
    }
    static RunApiException unavailable() {
        return new RunApiException(RunErrorCode.DEPENDENCY_UNAVAILABLE,"provider response unavailable; usage requires reconciliation");
    }
}
