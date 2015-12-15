package jycraft.plugin.servers;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import jycraft.plugin.JyCraftPlugin;
import jycraft.plugin.interpreter.PyInterpreter;
import jycraft.plugin.json.*;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import wshttpserver.HttpWebSocketServerListener;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles the Websocket behavior of the StaticFilesServer
 * It receives information from the coding environment on the same port the HttpServer is.
 * */

public class PySFListener implements HttpWebSocketServerListener {
    private JyCraftPlugin plugin;
    private String password;
    private Map<WebSocket, PyInterpreter> connections;
    private Map<WebSocket, String> buffers;
    private Map<WebSocket, Boolean> authorized;
    private JsonParser parser = new JsonParser();
    private Gson gson;


    public PySFListener(JyCraftPlugin caller, String password){
        this.plugin = caller;
        this.password = password;
        this.connections = new HashMap<WebSocket, PyInterpreter>();
        this.buffers = new HashMap<WebSocket, String>();
        this.authorized = new HashMap<WebSocket, Boolean>();
        this.gson = GsonUtils.getGson();
    }

    public String getPassword(){
        return this.password;
    }

    public JyCraftPlugin getPlugin() {
        return this.plugin;
    }

    public void close(WebSocket webSocket){
        connections.get(webSocket).close();
        connections.remove(webSocket);
        buffers.remove(webSocket);
        authorized.remove(webSocket);
    }

    @Override
    public boolean wssConnect(SelectionKey selectionKey) {
        // accept incoming connections
        return true;
    }

    @Override
    public void wssOpen(WebSocket webSocket, ClientHandshake chs) {
        plugin.log("New websocket connection");
        PyInterpreter interpreter = new PyInterpreter();
        OutputStream os = new SFLOutputStream(webSocket);
        interpreter.setOut(os);
        interpreter.setErr(os);
        connections.put(webSocket,interpreter);
        buffers.put(webSocket, "");
        authorized.put(webSocket, password == null || "".equals(password));
    }

    @Override
    public void wssClose(WebSocket webSocket, int i, String s, boolean b) {
        close(webSocket);
    }

    @Override
    public void wssMessage(WebSocket webSocket, String message) {
        boolean auth = authorized.get(webSocket);
        MessageType messageType;
        final TypeToken<Message> messageTypeToken = new TypeToken<Message>(){};
        final Message jsonMessage = gson.fromJson(message, messageTypeToken.getType());
        Status status;
        Message loginMessage;
        Message logoutMessage;

        messageType = MessageType.valueOf(jsonMessage.getType().toUpperCase());
        plugin.log(messageType.name());

        switch (messageType) {
            case LOGIN:
                if (!this.password.equals(jsonMessage.getPassword())) {
                    status = new Status(500, "Login failed");
                    loginMessage = new Message("login", status);
                    webSocket.send(this.gson.toJson(loginMessage));
                } else {
                    this.authorized.put(webSocket, true);
                    status = new Status(100, "Login successful");
                    loginMessage = new Message("login", status);
                    webSocket.send(this.gson.toJson(loginMessage));
                }
                return;
            case INTERACTIVE:
                if (!auth) {
                    status = new Status(501, "Not authenticated");
                    loginMessage = new Message("login", status);
                    webSocket.send(this.gson.toJson(loginMessage));
                    return;
                }
                boolean more = false;
                final PyInterpreter interpreter = connections.get(webSocket);
                String command = jsonMessage.getCommand();
                Message exmessage;
                try{
                    if (command.contains("\n")){
                        more = getPlugin().parse(interpreter, command, true);
                    }
                    else {
                        buffers.put(webSocket, buffers.get(webSocket) + "\n" + command);
                        more = getPlugin().parse(interpreter, buffers.get(webSocket), false);
                    }
                }
                catch (Exception e){
                    plugin.log("[Python] " + e.toString());
                    status = new Status(3, "Python Exception");
                    exmessage = new InteractiveMessage("interactive", status);
                    webSocket.send(this.gson.toJson(exmessage));
                }
                if (!more){
                    buffers.put(webSocket, "");
                }
                if (more){
                    status = new Status(101, "More input expected");
                    exmessage = new InteractiveMessage("interactive", status, "... ");
                    webSocket.send(this.gson.toJson(exmessage));
                }
                else {
                    status = new Status(102, "Expecting input");
                    exmessage = new Message("interactive", status, ">>> ");
                    webSocket.send(this.gson.toJson(exmessage));
                }
                break;
            case FILE:
                plugin.log("Not implemented Yet");
                break;
            case LOGOUT:
                status = new Status(100, "Logout successful");
                logoutMessage = new Message("login", status);
                webSocket.send(this.gson.toJson(logoutMessage));
                webSocket.close(CloseFrame.NORMAL);
                break;
            default:
                status = new Status(500, "Unidentified action type");
                logoutMessage = new Message("undefined", status);
                webSocket.send(this.gson.toJson(logoutMessage));
                break;
        }

    }

    @Override
    public void wssMessage(WebSocket webSocket, ByteBuffer message) {
        boolean auth = this.authorized.get(webSocket);
        Status status;
        Message loginMessage;
        InteractiveMessage exmessage;
        if (!auth){
            status = new Status(501, "Not authenticated");
            loginMessage = new Message("login", status);
            webSocket.send(this.gson.toJson(loginMessage));
            return;
        }
        else
        {
            status = new Status(4, "ByteBuffers not implemented yet");
            exmessage = new InteractiveMessage("execute", status, "ByteBuffers not implemented");
            webSocket.send(this.gson.toJson(exmessage));
            plugin.log("ByteBuffer message not implemented");
            return;
        }
    }



    @Override
    public void wssError(WebSocket webSocket, Exception e) {
        close(webSocket);
    }

    private class SFLOutputStream extends OutputStream {
        private WebSocket ws;
        private String buffer;
        private Gson gson;
        public SFLOutputStream(WebSocket ws){
            this.ws = ws;
            this.buffer = "";
            this.gson = GsonUtils.getGson();
        }

        @Override
        public void write(int b) {
            int[] bytes = { b };
            write(bytes, 0, bytes.length);
        }
        public void write(int[] bytes, int offset, int length) {
            Status status;
            Message jsonMessage;
            String s = new String(bytes, offset, length);
            this.buffer += s;
            if (this.buffer.endsWith("\n")) {
        // TODO: 15/12/15 FIX IllegalArgumentException types and labels must be unique while instatinating jsonmessage
                status = new Status(100, "Sending result");
                jsonMessage = new Message("interactive", status);
                jsonMessage.setResult(this.buffer);
                ws.send(this.gson.toJson(jsonMessage));
                plugin.log("[Python] "+this.buffer.substring(0, this.buffer.length()-1));
                buffer = "";
            }
        }
    }

}
