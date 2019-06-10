/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.base.start;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ofbiz.base.container.ContainerLoader;
import org.apache.ofbiz.base.start.Start.ServerState;
import org.apache.ofbiz.base.util.UtilValidate;

/**
 * The AdminServer provides a way to communicate with a running
 * OFBiz instance after it has started and send commands to that instance
 * such as inquiring on server status or requesting system shutdown
 */
final class AdminServer extends Thread {

    /**
     * Commands communicated between AdminClient and AdminServer
     */
    enum OfbizSocketCommand {
        SHUTDOWN, STATUS, FAIL
    }

    private ServerSocket serverSocket = null;
    private ContainerLoader loader;
    private AtomicReference<ServerState> serverState = null;
    private Config config = null;

    AdminServer(ContainerLoader loader, AtomicReference<ServerState> serverState, Config config) throws StartupException {
        super("OFBiz-AdminServer");
        try {
            this.serverSocket = new ServerSocket(config.adminPort, 1, config.adminAddress);
        } catch (IOException e) {
            throw new StartupException("Couldn't create server socket(" + config.adminAddress + ":" + config.adminPort + ")", e);
        }
        setDaemon(false);
        this.loader = loader;
        this.serverState = serverState;
        this.config = config;
    }

    @Override
    public void run() {
        System.out.println("Admin socket configured on - " + config.adminAddress + ":" + config.adminPort);
        while (!Thread.interrupted()) {
            try (Socket clientSocket = serverSocket.accept()){

                System.out.println("Received connection from - "
                        + clientSocket.getInetAddress() + " : "
                        + clientSocket.getPort());

                processClientRequest(clientSocket, loader, serverState);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void processClientRequest(Socket client, ContainerLoader loader, AtomicReference<ServerState> serverState)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true)) {

            // read client request and prepare response
            String clientRequest = reader.readLine();
            OfbizSocketCommand clientCommand = determineClientCommand(clientRequest);
            String serverResponse = prepareResponseToClient(clientCommand, serverState);

            // send response back to client
            writer.println(serverResponse);

            // if the client request is shutdown, execute shutdown sequence
            if(clientCommand.equals(OfbizSocketCommand.SHUTDOWN)) {
                writer.flush();
                StartupControlPanel.stop(loader, serverState, this);
            }
        }
    }

    private OfbizSocketCommand determineClientCommand(String request) {
        if(!isValidRequest(request)) {
            return OfbizSocketCommand.FAIL;
        }
        return OfbizSocketCommand.valueOf(request.substring(request.indexOf(':') + 1));
    }

    /**
     * Validates if request is a suitable String
     * @param request
     * @return boolean which shows if request is suitable
     */
    private boolean isValidRequest(String request) {
        return UtilValidate.isNotEmpty(request)
                && request.contains(":")
                && request.substring(0, request.indexOf(':')).equals(config.adminKey)
                && !request.substring(request.indexOf(':') + 1).isEmpty();
    }

    private static String prepareResponseToClient(OfbizSocketCommand control, AtomicReference<ServerState> serverState) {
        String response = null;
        switch(control) {
            case SHUTDOWN:
                if (serverState.get() == ServerState.STOPPING) {
                    response = "IN-PROGRESS";
                } else {
                    response = "OK";
                }
                break;
            case STATUS:
                response = serverState.get().toString();
                break;
            case FAIL:
                response = "FAIL";
                break;
        }
        return response;
    }
}
