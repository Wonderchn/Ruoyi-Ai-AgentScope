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

package com.nageoffer.ai.ragent.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="p3")
public class P3Properties {
    private boolean enabled;
    private boolean syntheticModel;
    public boolean isSyntheticModel(){return syntheticModel;}
    public void setSyntheticModel(boolean value){syntheticModel=value;}
    private final Sandbox sandbox=new Sandbox();
    private final Approval approval=new Approval();
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean value){enabled=value;}
    public Sandbox getSandbox(){return sandbox;}
    public Approval getApproval(){return approval;}
    public static class Sandbox {
        private boolean enabled;
        private String namespace="";
        private String credential="";
        public boolean isEnabled(){return enabled;}
        public void setEnabled(boolean value){enabled=value;}
        public String getNamespace(){return namespace;}
        public void setNamespace(String value){namespace=value;}
        public String getCredential(){return credential;}
        public void setCredential(String value){credential=value;}
    }
    public static class Approval {
        private boolean initiatorEnabled;
        public boolean isInitiatorEnabled(){return initiatorEnabled;}
        public void setInitiatorEnabled(boolean value){initiatorEnabled=value;}
    }
}
