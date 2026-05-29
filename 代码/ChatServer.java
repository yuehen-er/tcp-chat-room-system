import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {
    private static final int PORT = 8888;
    private static Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private static List<Message> chatHistory = new CopyOnWriteArrayList<>();
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  TCP多线程聊天室");
        System.out.println("========================================");
        System.out.println();
        System.out.println("[启动] 服务器正在启动...");
        
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[成功] 服务器已启动！");
            System.out.println("[信息] 端口: " + PORT);
            System.out.println();
            System.out.println("按 Ctrl+C 停止服务器");
            System.out.println("========================================");
            
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                new Thread(handler).start();
            }
        } catch (IOException e) {
            System.err.println("[错误] 服务器异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static void registerClient(String username, ClientHandler handler) {
        clients.put(username, handler);
        broadcastUserList();
    }
    
    public static void removeClient(String username) {
        clients.remove(username);
        broadcastUserList();
    }
    
    public static List<String> getOnlineUsers() {
        return new ArrayList<>(clients.keySet());
    }
    
    public static void sendToUser(String username, String message) {
        ClientHandler handler = clients.get(username);
        if (handler != null) {
            handler.sendMessage(message);
        }
    }
    
    public static void broadcastToAll(String message) {
        for (ClientHandler handler : clients.values()) {
            handler.sendMessage(message);
        }
    }
    
    public static void addHistory(Message msg) {
        chatHistory.add(msg);
        if (chatHistory.size() > 1000) {
            chatHistory = chatHistory.subList(500, 1000);
        }
    }
    
    public static List<Message> getHistory(String user1, String user2) {
        List<Message> result = new ArrayList<>();
        for (Message msg : chatHistory) {
            if ((msg.sender.equals(user1) && msg.receiver.equals(user2)) ||
                (msg.sender.equals(user2) && msg.receiver.equals(user1))) {
                result.add(msg);
            }
        }
        return result;
    }
    
    public static void broadcastUserList() {
        StringBuilder json = new StringBuilder("{\"type\":\"users\",\"users\":[");
        boolean first = true;
        for (String user : getOnlineUsers()) {
            if (!first) json.append(",");
            json.append("{\"username\":\"").append(escapeJson(user)).append("\",\"status\":\"online\"}");
            first = false;
        }
        json.append("]}");
        broadcastToAll(json.toString());
    }
    
    public static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}

class Message {
    String sender;
    String receiver;
    String content;
    String type;
    long timestamp;
    
    public Message(String sender, String receiver, String content, String type) {
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }
    
    public String toJson() {
        return "{\"senderId\":\"" + ChatServer.escapeJson(sender) + "\"," +
               "\"senderName\":\"" + ChatServer.escapeJson(sender) + "\"," +
               "\"content\":\"" + ChatServer.escapeJson(content) + "\"," +
               "\"messageType\":\"" + type + "\"," +
               "\"createdAt\":\"" + new java.util.Date(timestamp).toInstant().toString() + "\"}";
    }
}

class ClientHandler implements Runnable {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username;
    private boolean isRunning = true;
    
    public ClientHandler(Socket socket) {
        this.socket = socket;
    }
    
    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            
            String firstLine = in.readLine();
            if (firstLine == null) return;
            
            if (firstLine.startsWith("LOGIN:")) {
                username = firstLine.substring(6).trim();
                
                if (username.isEmpty()) {
                    sendMessage("{\"type\":\"error\",\"message\":\"用户名不能为空\"}");
                    socket.close();
                    return;
                }
                
                ChatServer.registerClient(username, this);
                
                System.out.println("[连接] " + username + " 加入");
                
                sendMessage("{\"type\":\"system\",\"content\":\"欢迎 " + ChatServer.escapeJson(username) + " 加入聊天室！\"}");
                ChatServer.broadcastToAll("{\"type\":\"system\",\"content\":\"" + ChatServer.escapeJson(username) + " 加入了聊天室\"}");
                ChatServer.broadcastUserList();
                
            } else {
                socket.close();
                return;
            }
            
            String line;
            while (isRunning && (line = in.readLine()) != null) {
                handleMessage(line);
            }
            
        } catch (IOException e) {
            System.err.println("[错误] 客户端处理异常: " + e.getMessage());
        } finally {
            cleanup();
        }
    }
    
    private void handleMessage(String line) {
        if (line.startsWith("MSG:")) {
            String[] parts = line.substring(4).split("\\|", 3);
            if (parts.length >= 3) {
                String target = parts[0];
                String content = parts[1];
                String type = parts[2];
                
                Message msg = new Message(username, target, content, type);
                ChatServer.addHistory(msg);
                
                String json = "{\"type\":\"chat\",\"message\":" + msg.toJson() + "}";
                ChatServer.sendToUser(target, json);
                sendMessage(json);
                
                System.out.println("[消息] " + username + " -> " + target + ": " + content);
            }
            
        } else if (line.equals("GET_USERS")) {
            ChatServer.broadcastUserList();
            
        } else if (line.startsWith("GET_HISTORY:")) {
            String[] parts = line.substring(12).split("\\|", 2);
            if (parts.length >= 2) {
                String target = parts[0];
                List<Message> history = ChatServer.getHistory(username, target);
                
                StringBuilder json = new StringBuilder("{\"type\":\"history\",\"messages\":[");
                for (int i = 0; i < history.size(); i++) {
                    if (i > 0) json.append(",");
                    json.append(history.get(i).toJson());
                }
                json.append("]}");
                sendMessage(json.toString());
            }
        }
    }
    
    public void sendMessage(String message) {
        if (out != null) {
            out.println(message);
        }
    }
    
    private void cleanup() {
        isRunning = false;
        if (username != null) {
            ChatServer.removeClient(username);
            System.out.println("[断开] " + username + " 离开");
            ChatServer.broadcastToAll("{\"type\":\"system\",\"content\":\"" + ChatServer.escapeJson(username) + " 离开了聊天室\"}");
        }
        
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            System.err.println("[错误] 清理资源失败: " + e.getMessage());
        }
    }
}
