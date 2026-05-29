const WebSocket = require('ws');

const PORT = 8888;
const clients = new Map();
const allUsers = new Map();
let messages = [];
const unreadMessages = new Map();

console.log('========================================');
console.log('  TCP多线程聊天室 (Node.js版)');
console.log('========================================');
console.log();
console.log('[启动] 服务器正在启动...');

const wss = new WebSocket.Server({ 
    port: PORT,
    perMessageDeflate: {
        zlibDeflateOptions: { level: 1 },
        zlibInflateOptions: { memLevel: 7 },
        threshold: 1024
    },
    maxPayload: 100 * 1024 * 1024,
    clientTracking: true
});

function forceLogout(username, reason) {
    if (clients.has(username)) {
        const oldWs = clients.get(username);
        try {
            oldWs.send(JSON.stringify({
                type: 'kicked',
                content: reason
            }));
            oldWs.close(1000, '用户在另一客户端登录');
        } catch (e) {
            console.error('[错误] 强制下线发送失败:', e);
        }
        clients.delete(username);
        console.log('[断开] ' + username + ' 被强制下线');
    }
}

wss.on('connection', (ws) => {
    let username = null;
    let isAuthenticated = false;
    let isForceClosed = false;

    ws.on('message', (data) => {
        if (isForceClosed) return;
        
        const message = data.toString();
        
        if (message.startsWith('LOGIN:')) {
            username = message.substring(6).trim();
            if (!username) return;
            
            if (clients.has(username)) {
                ws.send(JSON.stringify({
                    type: 'loginConflict',
                    content: '用户已在线，是否继续登录？',
                    username: username
                }));
                return;
            }
            
            clients.set(username, ws);
            allUsers.set(username, { username, status: 'online' });
            isAuthenticated = true;
            
            console.log('[连接] ' + username + ' 加入');
            
            ws.send(JSON.stringify({
                type: 'loginSuccess',
                content: '欢迎 ' + username + ' 加入聊天室！',
                username: username
            }));
            
            broadcast({
                type: 'system',
                content: username + ' 加入了聊天室'
            });
            
            broadcastUserList();
            sendUnreadCount(username);
            
        } else if (message.startsWith('FORCE_LOGIN:')) {
            const forceUsername = message.substring(12).trim();
            if (!forceUsername) return;
            
            forceLogout(forceUsername, '用户已在另一客户端登录，您已被强制下线');
            
            username = forceUsername;
            clients.set(username, ws);
            allUsers.set(username, { username, status: 'online' });
            isAuthenticated = true;
            
            console.log('[连接] ' + username + ' 强制登录');
            
            ws.send(JSON.stringify({
                type: 'loginSuccess',
                content: '欢迎 ' + username + ' 加入聊天室！',
                username: username
            }));
            
            broadcast({
                type: 'system',
                content: username + ' 加入了聊天室'
            });
            
            broadcastUserList();
            sendUnreadCount(username);
            
        } else if (isAuthenticated && !isForceClosed && message.startsWith('MSG:')) {
            const parts = message.substring(4).split('|', 3);
            if (parts.length >= 3) {
                const target = parts[0];
                const content = parts[1];
                const type = parts[2];
                
                const msg = {
                    id: Date.now(),
                    senderId: username,
                    senderName: username,
                    receiverId: target,
                    content: content,
                    messageType: type,
                    createdAt: new Date().toISOString(),
                    isRead: false
                };
                
                messages.push(msg);
                if (messages.length > 1000) {
                    messages = messages.slice(500);
                }
                
                console.log('[消息] ' + username + ' -> ' + target + ': ' + content);
                console.log('  消息结构:', JSON.stringify(msg));
                
                const messageData = {
                    type: 'chat',
                    message: msg
                };
                
                if (target === '群聊' || target === '群聊') {
                    // 群聊消息，给所有其他在线用户增加未读计数
                    for (const [otherUsername, otherWs] of clients.entries()) {
                        if (otherUsername !== username) {
                            const userUnread = unreadMessages.get(otherUsername) || new Map();
                            const currentCount = userUnread.get('群聊') || 0;
                            userUnread.set('群聊', currentCount + 1);
                            unreadMessages.set(otherUsername, userUnread);
                            sendUnreadCount(otherUsername);
                        }
                    }
                    broadcast(messageData);
                } else {
                    const targetWs = clients.get(target);
                    if (targetWs) {
                        targetWs.send(JSON.stringify(messageData));
                        if (target !== username) {
                            const userUnread = unreadMessages.get(target) || new Map();
                            const currentCount = userUnread.get(username) || 0;
                            userUnread.set(username, currentCount + 1);
                            unreadMessages.set(target, userUnread);
                            sendUnreadCount(target);
                        }
                    }
                    ws.send(JSON.stringify(messageData));
                }
            }
            
        } else if (isAuthenticated && !isForceClosed && message === 'GET_USERS') {
            broadcastUserList();
            
        } else if (isAuthenticated && !isForceClosed && message.startsWith('GET_HISTORY:')) {
            const parts = message.substring(12).split('|', 2);
            if (parts.length >= 2) {
                const target = parts[0];
                let history;
                
                if (target === '群聊' || target === '群聊') {
                    history = messages.filter(m => m.receiverId === '群聊' || m.receiverId === '群聊');
                } else {
                    history = messages.filter(m => 
                        (m.senderName === username && m.receiverId === target) ||
                        (m.senderName === target && m.receiverId === username)
                    );
                }
                
                ws.send(JSON.stringify({
                    type: 'history',
                    messages: history
                }));
            }
            
        } else if (isAuthenticated && !isForceClosed && message.startsWith('READ:')) {
            const sender = message.substring(5);
            const userUnread = unreadMessages.get(username) || new Map();
            userUnread.set(sender, 0);
            unreadMessages.set(username, userUnread);
            sendUnreadCount(username);
        }
    });

    ws.on('close', (code, reason) => {
        if (username && isAuthenticated && !isForceClosed) {
            if (clients.get(username) === ws) {
                clients.delete(username);
                console.log('[断开] ' + username + ' 离开');
                
                broadcast({
                    type: 'system',
                    content: username + ' 离开了聊天室'
                });
                
                broadcastUserList();
            }
        }
    });

    ws.on('error', (error) => {
        console.error('[错误] 连接错误:', error);
    });
});

function broadcast(data) {
    const json = JSON.stringify(data);
    for (const client of clients.values()) {
        if (client.readyState === WebSocket.OPEN) {
            client.send(json);
        }
    }
}

function broadcastUserList() {
    const userList = Array.from(allUsers.keys()).map(u => ({
        username: u,
        status: clients.has(u) ? 'online' : 'offline'
    }));
    
    broadcast({
        type: 'users',
        users: userList
    });
}

function sendUnreadCount(username) {
    const counts = {};
    const userUnread = unreadMessages.get(username) || new Map();
    for (const [sender, count] of userUnread) {
        counts[sender] = count;
    }
    
    const ws = clients.get(username);
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            type: 'unread',
            counts: counts
        }));
    }
}

console.log('[成功] WebSocket服务器已启动！');
console.log('[信息] 端口: ' + PORT);
console.log();
console.log('按 Ctrl+C 停止服务器');
console.log('========================================');
console.log();
