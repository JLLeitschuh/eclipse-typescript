/*
 * Copyright 2013 Palantir Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.typescript.tsbridge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.palantir.typescript.tsbridge.autocomplete.AutoCompleteResult;
import com.palantir.typescript.tsbridge.autocomplete.AutoCompleteService;
import com.palantir.typescript.tsbridge.autocomplete.CheckFileResult;
import com.palantir.typescript.tsbridge.syntaxhighlight.SyntaxHighlightResult;
import com.palantir.typescript.tsbridge.syntaxhighlight.SyntaxHighlightService;

/**
 * This handles all requests that need to be handled by TypeScript's built in language services.
 *
 * @author tyleradams
 */
public final class TypeScriptBridge {

    private static TypeScriptBridge BRIDGE = null;

    private static final String DEFAULT_NODE_LOCATION = "/usr/local/bin/node";
    private static final String DEFAULT_BRIDGE_LOCATION = "TSBridge/ecbuild/bridge.js";

    private BufferedReader fromServer;
    private BufferedWriter toServer;
    private Process server;

    private final String nodeLocation;
    private final String bridgeLocation;

    private final SyntaxHighlightService syntaxHighlightService;
    private final AutoCompleteService autoCompleteService;


    public TypeScriptBridge() {
        this(DEFAULT_NODE_LOCATION, DEFAULT_BRIDGE_LOCATION);
    }

    public TypeScriptBridge(String nodeLocation, String bridgeLocation) {

        Preconditions.checkNotNull(nodeLocation);
        Preconditions.checkNotNull(bridgeLocation);

        String pluginRoot = this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath();

        this.nodeLocation = nodeLocation;
        this.bridgeLocation = pluginRoot + bridgeLocation;

        initializeServer();

        this.syntaxHighlightService = new SyntaxHighlightService(this);
        this.autoCompleteService = new AutoCompleteService(this);
    }


    public SyntaxHighlightService getSyntaxHighlightService() {
        return this.syntaxHighlightService;
    }

    public AutoCompleteService getAutoCompleteService() {
        return this.autoCompleteService;
    }

    /**
     * This method handles packaging the request from Java, sending it across the TypeScript bridge,
     * and packaging the result for usage.
     */
    public IResult sendRequest(IRequest request) {
        Preconditions.checkNotNull(request);

        ObjectMapper mapper = new ObjectMapper();
        String rawResult = sendRequestGetRawResult(request, mapper);

        try {
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            IResult result = mapper.readValue(rawResult, SimpleResult.class);
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

            if (result.isResultNotValid()) {
                ErrorResult eResult = mapper.readValue(rawResult, ErrorResult.class);
                throw new RuntimeException("The result is not valid, the error message is: " + eResult.getErrorMessage());
            }
            //TODO: Automate this, I should not have to modify the bridge manually every time a new result appears.
            switch (result.getResultType()) {
                case SyntaxHighlightResult.TYPE:
                    result = mapper.readValue(rawResult, SyntaxHighlightResult.class);
                    break;
                case AutoCompleteResult.TYPE:
                    result = mapper.readValue(rawResult, AutoCompleteResult.class);
                    break;
                case CheckFileResult.TYPE:
                    result = mapper.readValue(rawResult, CheckFileResult.class);
                    break;
            }
            return result;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public static TypeScriptBridge startBridge() {
        if (BRIDGE != null) {
            throw new RuntimeException("We already started the Bridge, you cannot start it again");
        }
        BRIDGE = new TypeScriptBridge();
        return BRIDGE;
    }

    public static TypeScriptBridge getBridge() {
        if (BRIDGE == null) {
            throw new RuntimeException("The Bridge has not been started");
        }
        return BRIDGE;
    }

    public static void stopBridge() {
        if (BRIDGE == null) {
            throw new RuntimeException("The Bridge has not been started, you cannot stop it");
        }
        BRIDGE.killServer();
        BRIDGE = null;
        return;
    }

    private String sendRequestGetRawResult(IRequest request, ObjectMapper mapper) {
        String rawResult = "";
        String rawRequest;
        try {
            rawRequest = mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        try {
            this.toServer.write(rawRequest);
            this.toServer.flush();
            rawResult = this.fromServer.readLine();
        } catch (IOException e) {
            restartServer();
        }
        if (rawResult == null) {
            restartServer();
        }

        return rawResult;
    }

    private void initializeServer() {
        ProcessBuilder pb = new ProcessBuilder(this.nodeLocation, this.bridgeLocation);
        try {
            this.server = pb.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.fromServer = initializeReader();
        this.toServer = initializeWriter();

    }

    private BufferedReader initializeReader() {
        return new BufferedReader(
            new InputStreamReader(this.server.getInputStream()));
    }

    private BufferedWriter initializeWriter() {
        return new BufferedWriter(
            new OutputStreamWriter(this.server.getOutputStream()));
    }

    private void killServer() {
        try {
            this.fromServer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            this.toServer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void restartServer() {
        killServer();
        initializeServer();
    }
}
