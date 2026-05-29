import java.io.*;
import java.net.*;

public class ChatClient {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int PORT = 8888;
    private static volatile boolean isRunning = true;

    public static void main(String[] args) {
        printWelcome();

        try (
            Socket socket = new Socket(SERVER_ADDRESS, PORT);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in))
        ) {
            System.out.println("成功连接到聊天室服务器");
            System.out.println("正在等待服务器响应...\n");

            Thread receiveThread = new Thread(() -> {
                try {
                    String message;
                    while (isRunning && (message = in.readLine()) != null) {
                        System.out.println(message);
                        if (message.contains("您已退出聊天室")) {
                            break;
                        }
                    }
                } catch (IOException e) {
                    if (isRunning) {
                        System.err.println("[error] 读取服务器消息异常: " + e.getMessage());
                    }
                }
            });
            receiveThread.start();

            String input;
            while (isRunning && (input = userInput.readLine()) != null) {
                out.println(input);

                if (input.equalsIgnoreCase("/quit") || input.equalsIgnoreCase("/exit")) {
                    isRunning = false;
                    System.out.println("正在退出聊天室...");
                    Thread.sleep(500);
                    break;
                }
            }

            receiveThread.interrupt();
        } catch (ConnectException e) {
            System.err.println("[error] 无法连接到服务器，请确保服务器已启动");
            System.err.println("[error] 服务器地址: " + SERVER_ADDRESS + ":" + PORT);
        } catch (IOException e) {
            System.err.println("[error] 客户端异常: " + e.getMessage());
        } catch (InterruptedException e) {
            System.err.println("[error] 线程中断: " + e.getMessage());
        } finally {
            System.out.println("已断开连接");
        }
    }

    private static void printWelcome() {
        System.out.println("========================================");
        System.out.println("  基于TCP协议的多线程并发聊天室系统 - 客户端");
        System.out.println("========================================");
        System.out.println("连接到服务器: " + SERVER_ADDRESS + ":" + PORT);
        System.out.println();
        System.out.println("使用说明:");
        System.out.println("  输入 /help 查看帮助信息");
        System.out.println("  输入 /users 查看在线用户");
        System.out.println("  输入 /private <用户> <消息> 发送私聊");
        System.out.println("  输入 /quit 退出聊天室");
        System.out.println("========================================");
        System.out.println();
    }
}
