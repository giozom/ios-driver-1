/*
 * Copyright 2012 ios-driver committers.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.uiautomation.ios.server.command.impl;

import org.json.JSONObject;
import org.uiautomation.ios.communication.WebDriverLikeRequest;
import org.uiautomation.ios.exceptions.IOSAutomationException;
import org.uiautomation.ios.server.command.UIAScriptHandler;
import org.uiautomation.ios.server.command.impl.session.hack.TimeSpeeder;
import org.uiautomation.ios.server.instruments.SessionsManager;

// TODO should go away eventually.
public class CustomUIAScriptHandler extends UIAScriptHandler {

  private static final String capabilities =
      "var json = UIAutomation.getCapabilities() ;UIAutomation.createJSONResponse(':sessionId',0,json)";

  public CustomUIAScriptHandler(SessionsManager instruments, WebDriverLikeRequest request)
      throws Exception {
    super(instruments, request);
    String s = getScript(instruments, request);
    setJS(s);
  }

  private String getScript(SessionsManager instruments, WebDriverLikeRequest r) throws Exception {
    String s = "N/A";
    switch (r.getGenericCommand()) {
      case GET_SESSION:
        s = capabilities.replace(":sessionId", instruments.getCurrentSessionId());
        return s;

      default:
        throw new IOSAutomationException("NI");
    }
  }
}
