import asyncio
import websockets
import json
from datetime import datetime

PORT = 8888
clients = {}
messages = []

print("="*40)
print("  TCP多线程聊天室 (Python版)")
print("="*40)
print()
print("[启动] 服务器正在启动...")

async def broadcast(data):
    for client in clients.values():
        try:
            await client.send(json.dumps(data))
        except:
            pass

async def broadcast_user_list():
    user_list = [{"username": name, "status": "online"} for name in clients.keys()]
    await broadcast({
        "type": "users",
        "users": user_list
    })

async def handle_connection(websocket, path):
    username = None
    
    try:
        async for message in websocket:
            if message.startswith("LOGIN:"):
                username = message[6:].strip()
                if not username:
                    continue
                
                clients[username] = websocket
                print(f"[连接] {username} 加入")
                
                await websocket.send(json.dumps({
                    "type": "system",
                    "content": f"欢迎 {username} 加入聊天室！"
                }))
                
                await broadcast({
                    "type": "system",
                    "content": f"{username} 加入了聊天室"
                })
                
                await broadcast_user_list()
                
            elif message.startswith("MSG:"):
                parts = message[4:].split("|", 2)
                if len(parts) >= 2:
                    target = parts[0]
                    content = parts[1]
                    msg_type = parts[2] if len(parts) > 2 else "private"
                    
                    msg = {
                        "id": datetime.now().timestamp(),
                        "senderId": username,
                        "senderName": username,
                        "receiverId": target,
                        "content": content,
                        "messageType": msg_type,
                        "createdAt": datetime.now().isoformat(),
                        "isRead": False
                    }
                    
                    messages.append(msg)
                    if len(messages) > 1000:
                        messages[:] = messages[500:]
                    
                    print(f"[消息] {username} -> {target}: {content}")
                    
                    data = {
                        "type": "chat",
                        "message": msg
                    }
                    
                    if target in clients:
                        await clients[target].send(json.dumps(data))
                    
                    await websocket.send(json.dumps(data))
                    
            elif message == "GET_USERS":
                await broadcast_user_list()
                
            elif message.startswith("GET_HISTORY:"):
                parts = message[12:].split("|", 1)
                if len(parts) >= 1:
                    target = parts[0]
                    history = [m for m in messages 
                               if (m["senderName"] == username and m["receiverId"] == target) or
                                  (m["senderName"] == target and m["receiverId"] == username)]
                    
                    await websocket.send(json.dumps({
                        "type": "history",
                        "messages": history
                    }))
                    
    except Exception as e:
        print(f"[错误] 连接异常: {e}")
        
    finally:
        if username and username in clients:
            del clients[username]
            print(f"[断开] {username} 离开")
            
            await broadcast({
                "type": "system",
                "content": f"{username} 离开了聊天室"
            })
            
            await broadcast_user_list()

async def main():
    server = await websockets.serve(handle_connection, "localhost", PORT)
    print(f"[成功] WebSocket服务器已启动！")
    print(f"[信息] 端口: {PORT}")
    print()
    print("按 Ctrl+C 停止服务器")
    print("="*40)
    print()
    await server.wait_closed()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[关闭] 服务器已停止")
